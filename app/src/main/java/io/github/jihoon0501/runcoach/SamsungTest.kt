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
class SamsungRepo(private val context: Context) {

    private val store: HealthDataStore by lazy { HealthDataService.getStore(context) }
    private val permissions = setOf(Permission.of(DataTypes.EXERCISE, AccessType.READ))

    suspend fun hasPermission(): Boolean =
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
    private suspend fun readSessions(start: Instant, end: Instant): Pair<List<ExerciseSession>, List<WalkSession>> {
        val runs = mutableListOf<ExerciseSession>()
        val walks = mutableListOf<WalkSession>()
        var token: String? = null
        do {
            val builder = DataTypes.EXERCISE.readDataRequestBuilder
                .setInstantTimeFilter(InstantTimeFilter.of(start, end))
                .setOrdering(Ordering.ASC)
                .setPageSize(25)
            if (!token.isNullOrEmpty()) builder.setPageToken(token)
            val resp = store.readData(builder.build())
            for (point in resp.dataList) {
                point.getValue(DataType.ExerciseType.SESSIONS)?.forEach { sess ->
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
        return runs to walks
    }

    /**
     * [start]~[end] 러닝과 걷기를 30일 단위로 (최근 → 과거 순) 읽는다.
     * 한 단위를 읽을 때마다 지금까지 읽은 러닝/걷기 목록을 [onChunk]로 전달한다.
     */
    suspend fun fetchAll(
        start: Instant,
        end: Instant,
        onChunk: ((List<RunSession>, List<WalkSession>) -> Unit)? = null,
    ): Pair<List<RunSession>, List<WalkSession>> {
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
        return runOut.values.sortedBy { it.start } to walkOut.values.sortedBy { it.start }
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

    private val emptyAnalysis = Analysis(emptyList(), 0.0, 0, null, null, emptyList())

    private class Raw(var kind: SegKind, val startIdx: Int, var endIdx: Int)

    private fun kindOf(v: Double): SegKind = when {
        v < 0.5 -> SegKind.REST   // 거의 멈춤 (일시정지·신호 대기)
        v < 1.9 -> SegKind.WALK   // 약 8'46"/km보다 느림
        v < 2.3 -> SegKind.JOG    // 8'46" ~ 7'15"
        else -> SegKind.RUN       // 7'15"/km보다 빠름
    }

    /**
     * 로그의 속도(m/s)로 운동을 정지/걷기/조깅/달리기 구간으로 나누고,
     * 위치에 따라 준비운동 / 본 운동 / 회복 / 마무리 역할을 붙인다. 버리는 구간은 없다.
     * 본 운동(첫 달리기 ~ 마지막 달리기) 안의 1km 구간 시간과 본 운동 시간은 '정지한 시간'을 뺀 움직인 시간이다.
     * 값은 근사치(±수 초).
     */
    private fun analyze(s: ExerciseSession): Analysis {
        val fullLog = s.log ?: emptyList()
        val log = fullLog.filter { it.speed != null }.sortedBy { it.timestamp }
        val n = log.size
        if (n < 30) return emptyAnalysis
        val t = LongArray(n) { log[it].timestamp.toEpochMilli() }
        val v = DoubleArray(n) { (log[it].speed ?: 0f).toDouble() }

        // 심박은 속도와 다른 로그 항목에 들어 있어서 따로 모은다
        val hrLog = fullLog.filter { it.heartRate != null }.sortedBy { it.timestamp }
        val hrT = LongArray(hrLog.size) { hrLog[it].timestamp.toEpochMilli() }
        val hrV = DoubleArray(hrLog.size) { (hrLog[it].heartRate ?: 0f).toDouble() }
        fun hrAvg(from: Long, to: Long): Int? {
            var sum = 0.0
            var c = 0
            for (i in hrT.indices) {
                if (hrT[i] in from..to) {
                    sum += hrV[i]
                    c++
                }
            }
            return if (c > 0) (sum / c).toInt() else null
        }

        // 1) 속도 평활화(±4초 평균) 후 종류 분류
        val gaps = DoubleArray(n - 1) { (t[it + 1] - t[it]) / 1000.0 }.sorted()
        val medDt = gaps[gaps.size / 2].coerceAtLeast(0.5)
        val half = maxOf(1, Math.round(4.0 / medDt).toInt())   // 1초 간격이면 ±4칸, 5초 간격이면 ±1칸
        val sm = DoubleArray(n) { i ->
            val lo = maxOf(0, i - half)
            val hi = minOf(n - 1, i + half)
            var sum = 0.0
            for (k in lo..hi) sum += v[k]
            sum / (hi - lo + 1)
        }
        val kinds = Array(n) { kindOf(sm[it]) }

        // 2) 같은 종류끼리 묶기
        val raw = mutableListOf<Raw>()
        var st = 0
        for (i in 1..n) {
            if (i == n || kinds[i] != kinds[st]) {
                raw += Raw(kinds[st], st, i - 1)
                st = i
            }
        }

        // 3) 30초 미만의 짧은 구간은 더 긴 이웃에 흡수
        fun dur(r: Raw): Double = (t[r.endIdx] - t[r.startIdx]) / 1000.0 + 1.0
        while (raw.size > 1) {
            val i = raw.indexOfFirst { dur(it) < 30.0 }
            if (i < 0) break
            val left = if (i > 0) raw[i - 1] else null
            val right = if (i < raw.size - 1) raw[i + 1] else null
            val target = when {
                left == null -> right!!
                right == null -> left
                dur(left) >= dur(right) -> left
                else -> right
            }
            raw[i].kind = target.kind
            var j = 0
            while (j < raw.size - 1) {
                if (raw[j].kind == raw[j + 1].kind) {
                    raw[j].endIdx = raw[j + 1].endIdx
                    raw.removeAt(j + 1)
                } else j++
            }
        }

        val firstRun = raw.indexOfFirst { it.kind == SegKind.RUN }
        val lastRun = raw.indexOfLast { it.kind == SegKind.RUN }
        if (firstRun < 0) return emptyAnalysis

        // 움직인 시간(초): 정지 구간은 뺀다
        val mov = DoubleArray(n)
        for (i in 1 until n) {
            var dt = (t[i] - t[i - 1]) / 1000.0
            if (dt > 20.0) dt = 1.0
            mov[i] = mov[i - 1] + (if (kinds[i - 1] == SegKind.REST) 0.0 else dt)
        }

        // 4) 구간별 거리/시간/심박/케이던스
        val tStart = t[0]
        val segments = raw.mapIndexed { idx, r ->
            val role = when {
                idx < firstRun -> SegRole.WARMUP
                idx > lastRun -> SegRole.COOLDOWN
                r.kind == SegKind.RUN -> SegRole.MAIN
                else -> SegRole.RECOVERY
            }
            val endInt = if (idx < raw.size - 1) raw[idx + 1].startIdx else r.endIdx
            var d = 0.0
            for (i in r.startIdx + 1..endInt) {
                var dt = (t[i] - t[i - 1]) / 1000.0
                if (dt > 20.0) dt = 1.0
                d += v[i - 1] * dt
            }
            val cads = (r.startIdx..r.endIdx).mapNotNull { log[it].cadence?.toDouble()?.takeIf { c -> c > 0 } }
            RunSegment(
                role = role,
                kind = r.kind,
                startSec = (t[r.startIdx] - tStart) / 1000,
                durSec = (t[endInt] - t[r.startIdx]) / 1000,
                distanceKm = d / 1000.0,
                avgHr = hrAvg(t[r.startIdx], t[endInt]),
                cadence = if (cads.isNotEmpty()) cads.average().toInt() else null,
            )
        }

        // 5) 본 운동(첫 달리기 ~ 마지막 달리기) 안의 1km 구간 (정지 시간 제외)
        val mainStart = raw[firstRun].startIdx
        val mainEnd = raw[lastRun].endIdx
        var cum = 0.0
        var next = 1000.0
        var prevMov = mov[mainStart]
        val splits = mutableListOf<Int>()
        for (i in mainStart + 1..mainEnd) {
            var dt = (t[i] - t[i - 1]) / 1000.0
            if (dt > 20.0) dt = 1.0
            val seg = v[i - 1] * dt
            if (seg > 0 && cum + seg >= next) {
                val frac = (next - cum) / seg
                val mCross = mov[i - 1] + frac * (mov[i] - mov[i - 1])
                splits += Math.round(mCross - prevMov).toInt()
                prevMov = mCross
                next += 1000.0
            }
            cum += seg
        }

        val range = mainStart..mainEnd
        val cads = range.mapNotNull { log[it].cadence?.toDouble()?.takeIf { c -> c > 0 } }
        return Analysis(
            splits = splits,
            mainKm = cum / 1000.0,
            mainSec = Math.round(mov[mainEnd] - mov[mainStart]),
            avgHr = hrAvg(t[mainStart], t[mainEnd]),
            cadence = if (cads.isNotEmpty()) cads.average().toInt() else null,
            segments = segments,
        )
    }

    private fun toRunSession(s: ExerciseSession): RunSession {
        val a = analyze(s)
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
