package io.github.jihoon0501.runcoach

import android.app.Activity
import android.content.Context
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.InstantTimeFilter
import com.samsung.android.sdk.health.data.request.Ordering
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * 신체·체성분 기록 (측정일 단위). 체중계나 인바디 앱이 삼성 헬스 / Health Connect에 넣은 값을 읽는다.
 * 혈압 같은 의료성 정보는 다루지 않는다.
 */
data class BodyInfo(
    val date: LocalDate,
    val weightKg: Float?,
    val heightCm: Float?,
    val bmi: Float?,
    val bodyFatPct: Float?,
    val skeletalMuscleKg: Float? = null,   // 삼성 헬스(인바디 등이 넣은 경우)에서만 들어온다
)

/** 한 주(월요일 시작)의 러닝 거리와, 그 주에 측정한 신체 기록들 */
class BodyWeek(val monday: LocalDate, val runKm: Double, val items: List<BodyInfo>)

/** 선택한 데이터 소스에서 최근 6개월의 신체 기록을 읽어 폰에 저장한다 (이 폰 안에서만 쓴다). */
class BodyStore(context: Context, private val source: String = DataSourceKind.SAMSUNG) {

    companion object {
        private const val KEEP_YEARS = 5L          // 체중 변화 이력은 최대 5년치를 이 폰에 보관
        private const val FIRST_YEARS = 3L         // 처음 가져올 때 거슬러 올라가는 기간
        private const val OVERLAP_DAYS = 45L       // 이후에는 최근 45일만 다시 읽어서 합친다

        /**
         * 훈련량 대비 체중 급감 안내. 아래를 모두 만족할 때만 부드러운 점검 문구를 돌려준다 (아니면 null).
         *  - 최근 측정이 10일 안이고, 2~6주 간격의 두 체중 기록이 있다
         *  - 그 사이 체중이 3% 이상 줄었다
         *  - 최근 14일 러닝 거리가 10km 이상이고 그 전 14일보다 10% 이상 늘었다
         * 체중 감량을 권하거나 평가하지 않고, 식단·의료 조언도 하지 않는다.
         */
        fun advice(list: List<BodyInfo>, runs: List<RunSession>, today: LocalDate): String? {
            val w = list.filter { it.weightKg != null }.sortedBy { it.date.toEpochDay() }
            if (w.size < 2) return null
            val last = w.last()
            if (last.date.isBefore(today.minusDays(10))) return null
            val base = w.filter {
                val d = last.date.toEpochDay() - it.date.toEpochDay()
                d in 14..42
            }.minByOrNull { Math.abs(last.date.toEpochDay() - it.date.toEpochDay() - 28) } ?: return null
            val pct = (last.weightKg!! - base.weightKg!!) / base.weightKg!! * 100f
            if (pct > -3f) return null
            val recent = runs.filter { !it.date.isBefore(today.minusDays(14)) && !it.date.isAfter(today) }.sumOf { it.distanceKm }
            val prior = runs.filter { !it.date.isBefore(today.minusDays(28)) && it.date.isBefore(today.minusDays(14)) }.sumOf { it.distanceKm }
            if (recent < 10.0 || recent < prior * 1.1) return null
            val weeks = Math.max(2, Math.round((last.date.toEpochDay() - base.date.toEpochDay()) / 7f))
            return "최근 약 ${weeks}주 동안 체중이 약 ${"%.1f".format(-pct)}% 줄었고, 같은 기간 러닝 거리는 늘었어요. " +
                "훈련량을 늘리는 중에 체중이 빠르게 줄면 피로와 부상 위험이 커질 수 있어요. 충분히 먹고 쉬고 있는지 한 번 돌아보세요. " +
                "이 앱은 식단이나 의료 조언은 하지 않아요."
        }

        /** AI 평가에 함께 보낼 체중 변화량 한 줄 (최근 8주 기준, 변화량만). 비교할 기록이 부족하면 null */
        fun trendLine(list: List<BodyInfo>): String? {
            val w = list.filter { it.weightKg != null }.sortedBy { it.date.toEpochDay() }
            if (w.size < 2) return null
            val last = w.last()
            val base = w.lastOrNull { !it.date.isAfter(last.date.minusDays(56)) }
                ?: w.firstOrNull { last.date.toEpochDay() - it.date.toEpochDay() >= 14 } ?: return null
            val days = (last.date.toEpochDay() - base.date.toEpochDay()).toInt()
            if (days < 14) return null
            val diff = last.weightKg!! - base.weightKg!!
            val weeks = Math.max(1, Math.round(days / 7f))
            return "[참고] 최근 약 ${weeks}주 체중 변화 ${if (diff >= 0) "+" else ""}${"%.1f".format(diff)}kg. " +
                "체중 감량을 권하거나 칭찬·비판하지 말고, 필요할 때만 회복·훈련 부하 맥락에서 중립적으로 언급할 것."
        }

        /** 사용자가 켠 경우에만 AI 소개 문구에 체중 변화량을 붙인다 */
        fun updateAiContext(prefs: Prefs, list: List<BodyInfo>) {
            UserProfile.bodyTrend = if (prefs.bodyInAi) trendLine(list) else null
        }

        private fun mergeInfo(a: BodyInfo?, b: BodyInfo): BodyInfo =
            if (a == null) b else BodyInfo(
                b.date, b.weightKg ?: a.weightKg, b.heightCm ?: a.heightCm, b.bmi ?: a.bmi, b.bodyFatPct ?: a.bodyFatPct,
                b.skeletalMuscleKg ?: a.skeletalMuscleKg,
            )

        /**
         * 가장 최근 체중·키를 내 정보에 반영한다. 자동 반영이 꺼져 있거나, 이미 반영한 측정일보다 새로운 값이 없으면 하지 않는다
         * (그래서 사용자가 직접 고친 값은 더 새로운 측정값이 들어오기 전까지 유지된다). 값이 실제로 바뀌면 true.
         */
        fun applyToProfile(prefs: Prefs, list: List<BodyInfo>): Boolean {
            if (!prefs.bodyAutoApply) return false
            val wRec = list.filter { (it.weightKg ?: 0f) in 25f..300f }.maxByOrNull { it.date.toEpochDay() } ?: return false
            val latestH = list.filter { (it.heightCm ?: 0f) in 100f..250f }
                .maxByOrNull { it.date.toEpochDay() }?.heightCm
            val guardDate = wRec.date.toString()
            if (prefs.bodyAppliedDate.isNotEmpty() && guardDate <= prefs.bodyAppliedDate) return false
            val p = prefs.loadProfile()
            val w = wRec.weightKg?.let { Math.round(it) } ?: p.weightKg
            val h = latestH?.let { Math.round(it) } ?: p.heightCm
            prefs.bodyAppliedDate = guardDate
            if (w == p.weightKg && h == p.heightCm) return false
            val np = p.copy(weightKg = w, heightCm = h)
            prefs.saveProfile(np)
            UserProfile.data = np
            return true
        }

        /** 항목별로 가장 최근 값을 모은 '최근 신체 정보' (날짜는 가장 최근 측정일) */
        fun latest(list: List<BodyInfo>): BodyInfo? {
            if (list.isEmpty()) return null
            val s = list.sortedBy { it.date.toEpochDay() }
            return BodyInfo(
                date = s.last().date,
                weightKg = s.lastOrNull { it.weightKg != null }?.weightKg,
                heightCm = s.lastOrNull { it.heightCm != null }?.heightCm,
                bmi = s.lastOrNull { it.bmi != null }?.bmi,
                bodyFatPct = s.lastOrNull { it.bodyFatPct != null }?.bodyFatPct,
                skeletalMuscleKg = s.lastOrNull { it.skeletalMuscleKg != null }?.skeletalMuscleKg,
            )
        }

        /** [pick] 항목의 최근 값과 [days]일 전쯤(없으면 가장 오래된 기록)의 차이, 그 사이 일수. 비교할 기록이 부족하면 null */
        fun change(list: List<BodyInfo>, days: Int = 30, pick: (BodyInfo) -> Float?): Pair<Float, Int>? {
            val v = list.filter { pick(it) != null }.sortedBy { it.date.toEpochDay() }
            if (v.size < 2) return null
            val last = v.last()
            val base = v.lastOrNull { !it.date.isAfter(last.date.minusDays(days.toLong())) } ?: v.first()
            val d = (last.date.toEpochDay() - base.date.toEpochDay()).toInt()
            if (d <= 0) return null
            return (pick(last)!! - pick(base)!!) to d
        }

        /** 오늘이 속한 주까지 [weeks]주를 오래된 순으로: 주간 러닝 거리와 그 주의 신체 기록 */
        fun weekly(list: List<BodyInfo>, runs: List<RunSession>, weeks: Int, today: LocalDate): List<BodyWeek> {
            val thisMonday = PlanEngine.mondayOf(today)
            return (weeks - 1 downTo 0).map { i ->
                val mon = thisMonday.minusWeeks(i.toLong())
                val end = mon.plusDays(6)
                BodyWeek(
                    mon,
                    runs.filter { !it.date.isBefore(mon) && !it.date.isAfter(end) }.sumOf { it.distanceKm },
                    list.filter { !it.date.isBefore(mon) && !it.date.isAfter(end) },
                )
            }
        }

        /** 최근 체중과 30일 전쯤(없으면 가장 오래된 기록)의 차이(kg)와 그 사이 일수 */
        fun weightChange(list: List<BodyInfo>): Pair<Float, Int>? {
            val w = list.filter { it.weightKg != null }.sortedBy { it.date.toEpochDay() }
            if (w.size < 2) return null
            val last = w.last()
            val base = w.lastOrNull { !it.date.isAfter(last.date.minusDays(30)) } ?: w.first()
            val days = (last.date.toEpochDay() - base.date.toEpochDay()).toInt()
            if (days <= 0) return null
            return (last.weightKg!! - base.weightKg!!) to days
        }
    }

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "body_cache.json")
    private val samsung: HealthDataStore by lazy { HealthDataService.getStore(appContext) }
    private val perms = setOf(Permission.of(DataTypes.BODY_COMPOSITION, AccessType.READ))

    /** 저장 형식 버전. 2부터 골격근량이 들어가서, 이전 형식이면 처음처럼 전체 기간을 다시 읽는다 */
    private fun cacheVersion(): Int = try {
        val t = file.readText().trim()
        if (t.startsWith("{")) JSONObject(t).optInt("v", 1) else 1
    } catch (e: Exception) {
        0
    }

    fun load(): List<BodyInfo> = try {
        val t = file.readText().trim()
        val a = if (t.startsWith("{")) JSONObject(t).getJSONArray("items") else JSONArray(t)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            fun f(k: String): Float? = o.optDouble(k).takeIf { !it.isNaN() }?.toFloat()
            BodyInfo(LocalDate.parse(o.getString("d")), f("w"), f("h"), f("b"), f("f"), f("m"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(list: List<BodyInfo>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().apply {
                put("d", it.date.toString())
                it.weightKg?.let { v -> put("w", v.toDouble()) }
                it.heightCm?.let { v -> put("h", v.toDouble()) }
                it.bmi?.let { v -> put("b", v.toDouble()) }
                it.bodyFatPct?.let { v -> put("f", v.toDouble()) }
                it.skeletalMuscleKg?.let { v -> put("m", v.toDouble()) }
            })
        }
        file.writeText(JSONObject().apply { put("v", 2); put("items", a) }.toString())
    }

    suspend fun hasPermission(): Boolean {
        if (source == DataSourceKind.HEALTH_CONNECT) return HealthConnectRepo(appContext).hasBodyPermission()
        return samsung.getGrantedPermissions(perms).size >= perms.size
    }

    /** 삼성 헬스의 체성분 읽기 권한을 요청한다 (Health Connect는 화면에서 별도 런처로 요청) */
    suspend fun request(activity: Activity): Boolean {
        val granted = samsung.requestPermissions(perms, activity).map { it.dataType.name }.toSet()
        return DataTypes.BODY_COMPOSITION.name in granted
    }

    /**
     * 처음에는 최근 3년, 이후에는 최근 45일을 읽어서 저장된 이력에 합치고(같은 날짜는 새 값으로) 돌려준다.
     * 체중 변화 이력을 보여 주려고 최대 5년치를 이 폰에 보관한다.
     */
    suspend fun refresh(full: Boolean = false): List<BodyInfo> = withContext(Dispatchers.IO) {
        val cached = load()
        val to = Instant.now()
        val readAll = full || cached.isEmpty() || cacheVersion() < 2
        val from = if (readAll) to.minus(Duration.ofDays(365L * FIRST_YEARS)) else to.minus(Duration.ofDays(OVERLAP_DAYS))
        val fresh = if (source == DataSourceKind.HEALTH_CONNECT) {
            HealthConnectRepo(appContext).fetchBody(from, to)
        } else {
            fetchSamsung(from, to)
        }
        val byDate = LinkedHashMap<LocalDate, BodyInfo>()
        (cached + fresh).filter { it.weightKg != null || it.bodyFatPct != null || it.heightCm != null || it.skeletalMuscleKg != null }.forEach {
            byDate[it.date] = mergeInfo(byDate[it.date], it)
        }
        val limit = LocalDate.now().minusYears(KEEP_YEARS)
        val list = byDate.values.filter { it.date.isAfter(limit) }.sortedBy { it.date.toEpochDay() }
        save(list)
        list
    }

    private suspend fun fetchSamsung(from: Instant, to: Instant): List<BodyInfo> {
        val pts = mutableListOf<HealthDataPoint>()
        var token: String? = null
        do {
            val b = DataTypes.BODY_COMPOSITION.readDataRequestBuilder
                .setInstantTimeFilter(InstantTimeFilter.of(from, to))
                .setOrdering(Ordering.ASC).setPageSize(50)
            if (!token.isNullOrEmpty()) b.setPageToken(token)
            val resp = samsung.readData(b.build())
            pts += resp.dataList
            token = resp.pageToken
        } while (!token.isNullOrEmpty() && pts.size < 2000)
        return pts.map { p ->
            BodyInfo(
                date = p.getStartLocalDateTime().toLocalDate(),
                weightKg = p.getValue(DataType.BodyCompositionType.WEIGHT),
                heightCm = p.getValue(DataType.BodyCompositionType.HEIGHT),
                bmi = p.getValue(DataType.BodyCompositionType.BODY_MASS_INDEX),
                bodyFatPct = p.getValue(DataType.BodyCompositionType.BODY_FAT),
                skeletalMuscleKg = p.getValue(DataType.BodyCompositionType.SKELETAL_MUSCLE_MASS),
            )
        }
    }
}
