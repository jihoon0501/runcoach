package io.github.jihoon0501.runcoach

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.data.entries.ExerciseSession
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.InstantTimeFilter
import com.samsung.android.sdk.health.data.request.Ordering
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/** 삼성 헬스 Data SDK로 운동 세션을 직접 읽는다 (삼성 헬스 개발자 모드 필요) */
class SamsungRepo(private val context: Context) : RunSource {

    private val store: HealthDataStore by lazy { HealthDataService.getStore(context) }
    private val permissions = setOf(
        Permission.of(DataTypes.EXERCISE,          AccessType.READ),
        Permission.of(DataTypes.EXERCISE_LOCATION, AccessType.READ),  // GPS 경로
    )

    override suspend fun hasPermission(): Boolean =
        store.getGrantedPermissions(permissions).containsAll(permissions)

    suspend fun requestPermission(activity: Activity): Boolean =
        store.requestPermissions(permissions, activity).containsAll(permissions)

    /** 거리가 너무 짧은 걷기(이동 중 자동 감지 등)는 버리고, 나머지는 요약만 남긴다 */
    private fun toWalk(s: ExerciseSession): WalkSession? {
        val km = (s.distance ?: 0f) / 1000.0
        if (km < 0.3) return null
        return WalkSession(s.startTime, s.duration.seconds, km, s.meanHeartRate?.toInt())
    }

    /**
     * [start]~[end] 사이의 러닝 세션과 걷기 요약을 읽는다.
     * 러닝은 분석을 위해 세션 전체를 들고 있고, 걷기는 읽자마자 요약만 남겨서 메모리를 아낀다.
     */
    /**
     * ExerciseSession을 읽고 각 세션의 GPS 경로(ExerciseLocation)를 함께 가져온다.
     * readAssociatedData: ExerciseType + ExerciseLocationType 조합으로 한 번에 읽음.
     * GPS 경로가 포함된 세션은 route 필드가 채워진다.
     */
    private suspend fun readSessions(start: Instant, end: Instant): Pair<List<ExerciseSession>, List<WalkSession>> {
        val runs = mutableListOf<ExerciseSession>()
        val walks = mutableListOf<WalkSession>()
        var token: String? = null

        // EXERCISE_LOCATION 권한이 허용되면 ExerciseSession.route에 GPS가 자동으로 채워짐
        // 별도 연관 읽기 불필요 — toRunSession()에서 s.route 직접 사용
        val routeMap = emptyMap<Long, List<com.samsung.android.sdk.health.data.data.entries.ExerciseLocation>>()

        // 2단계: ExerciseSession 읽기 (기존 방식)
        do {
            val builder = DataTypes.EXERCISE.readDataRequestBuilder
                .setInstantTimeFilter(InstantTimeFilter.of(start, end))
                .setOrdering(Ordering.ASC)
                .setPageSize(25)
            if (!token.isNullOrEmpty()) builder.setPageToken(token)
            val resp = store.readData(builder.build())
            for (point in resp.dataList) {
                point.getValue(DataType.ExerciseType.SESSIONS)?.forEach { sess ->
                    // routeMap에서 GPS 경로를 찾아 세션에 주입할 수 없으므로
                    // toRunSessionWithRoute()에서 처리
                    when (sess.exerciseType) {
                        DataType.ExerciseType.PredefinedExerciseType.RUNNING,
                        DataType.ExerciseType.PredefinedExerciseType.TRACK_RUNNING -> runs += sess
                        DataType.ExerciseType.PredefinedExerciseType.WALKING -> toWalk(sess)?.let { walks += it }
                        else -> Unit
                    }
                }
            }
            token = resp.pageToken
        } while (!token.isNullOrEmpty())

        // routeMap을 companion 로컬 캐시에 저장 — toRunSession에서 참조
        pendingRoutes = routeMap
        return runs to walks
    }

    /** readSessions에서 채워지는 GPS 경로 임시 캐시 (startTime ms → 위치 목록) */
    private var pendingRoutes: Map<Long, List<com.samsung.android.sdk.health.data.data.entries.ExerciseLocation>> = emptyMap()

    /**
     * [start]~[end] 러닝과 걷기를 30일 단위로 (최근 → 과거 순) 읽는다.
     * 한 단위를 읽을 때마다 지금까지 읽은 러닝/걷기 목록을 [onChunk]로 전달한다.
     */
    override suspend fun fetchAll(
        start: Instant,
        end: Instant,
        onChunk: ((List<RunSession>, List<WalkSession>) -> Unit)?,
    ): Pair<List<RunSession>, List<WalkSession>> = try {
        val step = Duration.ofDays(30)
        val runOut = LinkedHashMap<Long, RunSession>()
        val walkOut = LinkedHashMap<Long, WalkSession>()
        var to = end
        while (to.isAfter(start)) {
            val prev = to.minus(step)
            val from = if (prev.isAfter(start)) prev else start
            // 경계에 걸친 세션을 놓치지 않도록 1시간 겹쳐 읽고, 같은 세션은 시작 시각으로 중복 제거
            val (sessions, walks) = readSessions(from.minusSeconds(3600), to)
            val converted = withContext(Dispatchers.Default) {
                sessions.map { toRunSession(it) }.filter { it.distanceKm >= 0.1 }   // 거리 0인 빈 세션 제외
            }
            converted.forEach { runOut[it.start.toEpochMilli()] = it }
            walks.forEach { walkOut[it.start.toEpochMilli()] = it }
            onChunk?.invoke(runOut.values.sortedBy { it.start }, walkOut.values.sortedBy { it.start })
            to = from
        }
        runOut.values.sortedBy { it.start } to walkOut.values.sortedBy { it.start }
    } catch (e: Throwable) {
        // NoClassDefFoundError 등 삼성 헬스 SDK 초기화 실패
        throw RuntimeException("삼성 헬스 SDK 오류: ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** 러닝만 필요할 때 */
    suspend fun fetchRuns(
        start: Instant,
        end: Instant,
        onChunk: ((List<RunSession>) -> Unit)? = null,
    ): List<RunSession> = fetchAll(start, end, onChunk?.let { cb -> { r, _ -> cb(r) } }).first

    /** 최근 [days]일 러닝 전체 (테스트용) */
    suspend fun loadRuns(days: Long = Config.LOAD_DAYS): List<RunSession> {
        val end = Instant.now()
        return fetchRuns(end.minus(Duration.ofDays(days)), end)
    }

    private class Analysis(
        val splits: List<Int>,
        val mainKm: Double,
        val mainSec: Long,
        val avgHr: Int?,
        val cadence: Int?,
        val segments: List<RunSegment>,
    )

    /**
     * 로그의 속도(m/s)로 운동을 정지/걷기/조깅/달리기 구간으로 나누고, 위치에 따라 준비운동 / 본 운동 / 회복 / 마무리 역할을 붙인다.
     * 분석은 Health Connect·파일 가져오기와 같은 [RunAnalyzer]가 한다 (인터벌의 준비운동·회복이 반복에 섞이지 않게 보정 포함).
     * 값은 근사치(±수 초). 삼성 SDK의 운동 기록에는 시계가 정한 인터벌(랩) 정보가 없어서 속도로 추정한다.
     */
    private fun analyze(s: ExerciseSession): Analysis {
        val log = s.log ?: emptyList()
        val speed = log.mapNotNull { e -> e.speed?.let { e.timestamp.toEpochMilli() to it.toDouble() } }
        val hr = log.mapNotNull { e -> e.heartRate?.let { e.timestamp.toEpochMilli() to it.toDouble() } }
        val cad = log.mapNotNull { e -> e.cadence?.takeIf { it > 0f }?.let { e.timestamp.toEpochMilli() to it.toDouble() } }
        val r = RunAnalyzer.analyze(speed, hr, cad)
        return Analysis(r.splits, r.mainKm, r.mainSec, r.avgHr, r.cadence, r.segments)
    }

    /**
     * 삼성 헬스 ExerciseLocation(GPS 경로)을 읽어서 RoutePoint 목록으로 변환한다.
     * Exercise with exercise location 권한이 승인된 뒤에 사용 가능.
     */
    /**
     * 삼성 헬스 SDK ExerciseLocation으로 GPS 경로를 읽는다.
     * TODO: AssociatedReadRequest(ExerciseType + ExerciseLocationType) 방식으로 구현 예정.
     * 현재는 빈 목록을 반환하며, GPS 경로는 Health Connect ExerciseRoute 또는
     * FIT 파일 가져오기를 통해 채워진다.
     */
    // GPS 경로는 toRunSession()에서 ExerciseSession.route로 직접 읽어요.
    // fetchRoute는 더 이상 필요하지 않지만 인터페이스 호환성을 위해 유지해요.
    suspend fun fetchRoute(start: java.time.Instant, end: java.time.Instant): List<RoutePoint> =
        emptyList()

    private fun toRunSession(s: ExerciseSession): RunSession {
        val a = analyze(s)
        // GPS 경로: ExerciseSession.route (삼성 헬스 직접 포함) 또는 pendingRoutes (연관 읽기) 중 더 긴 것 사용
        val sessionRoute = s.route.orEmpty()
        val assocRoute = pendingRoutes[s.startTime.toEpochMilli()] ?: emptyList()
        val bestRoute = if (assocRoute.size > sessionRoute.size) assocRoute else sessionRoute
        // ExerciseLog에서 포인트별 HR·케이던스·속도 추출 (타임스탬프 기준 보간)
        val log = s.log ?: emptyList()
        val logHr  = log.mapNotNull { e -> e.heartRate?.let { e.timestamp.toEpochMilli() to it.toInt() } }
        val logCad = log.mapNotNull { e -> e.cadence?.takeIf { it > 0f }?.let { e.timestamp.toEpochMilli() to it.toInt() } }
        val logSpd = log.mapNotNull { e -> e.speed?.let { e.timestamp.toEpochMilli() to it.toDouble() } }

        fun <T : Number> interpolate(series: List<Pair<Long, T>>, tsMs: Long): T? {
            if (series.isEmpty()) return null
            val idx = series.indexOfFirst { it.first >= tsMs }
            return when {
                idx == 0 -> series.first().second
                idx < 0  -> series.last().second
                else     -> series[idx - 1].second  // 가장 가까운 이전 값
            }
        }

        val route = bestRoute.mapNotNull { loc ->
            runCatching {
                val ts = loc.timestamp.toEpochMilli()
                RoutePoint(
                    lat         = loc.latitude.toDouble(),
                    lng         = loc.longitude.toDouble(),
                    altitudeM   = loc.altitude?.toDouble() ?: 0.0,
                    accuracyM   = loc.accuracy ?: 0f,
                    timestampMs = ts,
                    hr          = interpolate(logHr,  ts),
                    cadence     = interpolate(logCad, ts),
                    speedMps    = interpolate(logSpd, ts),
                )
            }.getOrNull()
        }
        return RunSession(
            start = s.startTime,
            durationSec = s.duration.seconds,
            distanceKm = (s.distance ?: 0f) / 1000.0,
            avgHr = s.meanHeartRate?.toInt() ?: a.avgHr,
            maxHr = s.maxHeartRate?.toInt(),
            cadence = a.cadence ?: s.meanCadence?.toInt(),
            lapCount = 0,
            title = s.customTitle,
            splits = a.splits,
            mainKm = a.mainKm,
            mainSec = a.mainSec,
            vo2Max = s.vo2Max,
            segments = a.segments,
            route = route,
        )
    }

    suspend fun diagnosticJson(days: Long = Config.LOAD_DAYS): String = runsDiagnosticJson(loadRuns(days))
}

/** 진단/공유용 JSON (러닝별 요약 + 구간) */
fun runsDiagnosticJson(runs: List<RunSession>): String {
    val arr = JSONArray()
    runs.forEach { r ->
        arr.put(JSONObject().apply {
            put("start", r.start.toString())
            put("date", r.date.toString())
            put("totalKm", Math.round(r.distanceKm * 100) / 100.0)
            put("mainKm", Math.round(r.mainKm * 100) / 100.0)
            put("mainSec", r.mainSec)
            put("paceSecPerKm", Math.round(r.paceSecPerKm))
            put("avgHr", r.avgHr ?: JSONObject.NULL)
            put("maxHr", r.maxHr ?: JSONObject.NULL)
            put("cadence", r.cadence ?: JSONObject.NULL)
            put("vo2Max", r.vo2Max ?: JSONObject.NULL)
            put("routePoints", r.route.size)   // GPS 경로 포인트 수 (0이면 GPS 없음)
             put("splitsSec", JSONArray(r.splits))
            put("segments", JSONArray(r.segments.map { g ->
                JSONObject().apply {
                    put("label", g.label)
                    put("startSec", g.startSec)
                    put("durSec", g.durSec)
                    put("km", Math.round(g.distanceKm * 100) / 100.0)
                    put("paceSecPerKm", Math.round(g.paceSecPerKm))
                    put("avgHr", g.avgHr ?: JSONObject.NULL)
                    put("cadence", g.cadence ?: JSONObject.NULL)
                }
            }))
        })
    }
    return JSONObject().apply {
        put("generatedAt", Instant.now().toString())
        put("runs", arr)
    }.toString(2)
}

@Composable
fun SamsungTestCard() {
    val context = LocalContext.current
    val repo = remember { SamsungRepo(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var status by remember { mutableStateOf("대기 중") }

    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("삼성 헬스 직접 읽기 (테스트)")
        Spacer(Modifier.height(4.dp))
        Text(
            "삼성 헬스 개발자 모드(데이터 읽기)를 켠 뒤 사용하세요. 결과 JSON을 복사해 Claude 대화에 붙여넣을 수 있어요.",
            color = RunColors.TextDim, fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        status = try {
                            val activity = context as Activity
                            if (repo.requestPermission(activity)) "권한 허용됨" else "권한 거부됨"
                        } catch (e: Throwable) {
                            "오류: ${e::class.java.simpleName}: ${e.message}"
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = RunColors.Lime, contentColor = RunColors.Bg),
            ) { Text("권한 요청", fontWeight = FontWeight.Bold) }

            OutlinedButton(onClick = {
                scope.launch {
                    status = "읽는 중..."
                    status = try {
                        val json = repo.diagnosticJson(Config.LOAD_DAYS)
                        clipboard.setText(AnnotatedString(json))
                        "복사됨 (${json.length}자). 대화에 붙여넣으세요."
                    } catch (e: Throwable) {
                        "오류: ${e::class.java.simpleName}: ${e.message}"
                    }
                }
            }) { Text("읽고 JSON 복사", color = RunColors.Lime) }
        }
        Spacer(Modifier.height(6.dp))
        Text(status, color = Color.White, fontSize = 11.sp)
    }
}
