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
import com.samsung.android.sdk.health.data.request.LocalDateFilter
import com.samsung.android.sdk.health.data.request.Ordering
import com.samsung.android.sdk.health.data.request.ReadDataRequest
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt

/** 건강 데이터 종류별 진단 결과 */
data class HealthTypeStat(
    val key: String,          // 영문 키
    val label: String,        // 화면에 보여줄 이름
    val granted: Boolean,     // 읽기 권한 허용 여부
    val windowDays: Int,      // 확인한 기간(일)
    val points: Int,          // 읽힌 기록 개수
    val days: Int,            // 기록이 있는 날짜 수
    val first: LocalDate?,
    val last: LocalDate?,
    val summary: String,      // 한 줄 요약 (평균 등)
    val error: String? = null,
)

/**
 * 삼성 헬스에서 '맞춤형 러닝 추천'에 쓸 후보 데이터(수면, 심박, 에너지 점수, 피부 온도, 혈중 산소, 체성분)가
 * 최근에 얼마나 들어와 있는지 확인한다. 원시 기록은 저장하지 않고 개수와 평균 같은 요약만 계산한다.
 */
class HealthDiag(context: Context) {

    private val appContext = context.applicationContext
    private val store: HealthDataStore by lazy { HealthDataService.getStore(appContext) }

    private class Spec(val key: String, val label: String, val type: DataType)

    private val specs = listOf(
        Spec("sleep", "수면", DataTypes.SLEEP),
        Spec("heart_rate", "심박(하루)", DataTypes.HEART_RATE),
        Spec("energy_score", "에너지 점수", DataTypes.ENERGY_SCORE),
        Spec("skin_temperature", "피부 온도", DataTypes.SKIN_TEMPERATURE),
        Spec("blood_oxygen", "혈중 산소", DataTypes.BLOOD_OXYGEN),
        Spec("body_composition", "체성분", DataTypes.BODY_COMPOSITION),
    )

    private val permissions = specs.map { Permission.of(it.type, AccessType.READ) }.toSet()

    val total: Int get() = specs.size

    private suspend fun grantedNames(): Set<String> =
        store.getGrantedPermissions(permissions).map { it.dataType.name }.toSet()

    /** 필요한 읽기 권한을 한 번에 요청하고, 허용된 종류 수를 돌려준다 */
    suspend fun requestAll(activity: Activity): Int {
        val granted = store.requestPermissions(permissions, activity).map { it.dataType.name }.toSet()
        return specs.count { it.type.name in granted }
    }

    private suspend fun readAll(maxPoints: Int, make: (String?) -> ReadDataRequest<HealthDataPoint>): List<HealthDataPoint> {
        val out = mutableListOf<HealthDataPoint>()
        var token: String? = null
        do {
            val resp = store.readData(make(token))
            out += resp.dataList
            token = resp.pageToken
        } while (!token.isNullOrEmpty() && out.size < maxPoints)
        return out
    }

    private fun stat(
        spec: Spec,
        windowDays: Int,
        pts: List<HealthDataPoint>,
        dateOf: (HealthDataPoint) -> LocalDate,
        summary: String,
    ): HealthTypeStat {
        val dates = pts.map(dateOf).toSortedSet()
        return HealthTypeStat(
            spec.key, spec.label, true, windowDays, pts.size, dates.size,
            dates.firstOrNull(), dates.lastOrNull(), summary,
        )
    }

    private fun avgText(values: List<Float>, unit: String, digits: Int = 1): String =
        if (values.isEmpty()) "값 없음" else "평균 " + "%.${digits}f".format(values.average()) + unit

    /** 최근 [windowDays]일(심박은 7일, 체성분은 3배 기간)을 확인한다 */
    suspend fun run(windowDays: Int = 30): List<HealthTypeStat> {
        val granted = grantedNames()
        val end = Instant.now()
        val start = end.minus(Duration.ofDays(windowDays.toLong()))
        val today = LocalDate.now()
        val startDate = today.minusDays(windowDays.toLong())

        return specs.map { spec ->
            if (spec.type.name !in granted) {
                return@map HealthTypeStat(spec.key, spec.label, false, windowDays, 0, 0, null, null, "권한 없음")
            }
            try {
                when (spec.key) {
                    "sleep" -> {
                        val pts = readAll(2000) { tk ->
                            val b = DataTypes.SLEEP.readDataRequestBuilder
                                .setInstantTimeFilter(InstantTimeFilter.of(start, end))
                                .setOrdering(Ordering.ASC).setPageSize(50)
                            if (!tk.isNullOrEmpty()) b.setPageToken(tk)
                            b.build()
                        }
                        val durs = pts.mapNotNull { it.getValue(DataType.SleepType.DURATION) }
                        val scores = pts.mapNotNull { it.getValue(DataType.SleepType.SLEEP_SCORE) }
                        val hours = if (durs.isNotEmpty()) durs.map { it.seconds }.average() / 3600.0 else null
                        val text = buildString {
                            append("기록 ${pts.size}개")
                            if (hours != null) append(" · 평균 %.1f시간".format(hours))
                            if (scores.isNotEmpty()) append(" · 수면 점수 ${scores.size}건(평균 ${scores.average().roundToInt()})")
                            else append(" · 수면 점수 없음")
                        }
                        stat(spec, windowDays, pts, { (it.getEndLocalDateTime() ?: it.getStartLocalDateTime()).toLocalDate() }, text)
                    }
                    "heart_rate" -> {
                        val hrDays = 7
                        val hrStart = end.minus(Duration.ofDays(hrDays.toLong()))
                        val pts = readAll(3000) { tk ->
                            val b = DataTypes.HEART_RATE.readDataRequestBuilder
                                .setInstantTimeFilter(InstantTimeFilter.of(hrStart, end))
                                .setOrdering(Ordering.ASC).setPageSize(200)
                            if (!tk.isNullOrEmpty()) b.setPageToken(tk)
                            b.build()
                        }
                        val series = pts.sumOf { it.getValue(DataType.HeartRateType.SERIES_DATA)?.size ?: 0 }
                        val mins = pts.mapNotNull { it.getValue(DataType.HeartRateType.MIN_HEART_RATE) }
                        val starts = pts.map { it.startTime.toEpochMilli() }.sorted()
                        val gaps = starts.zipWithNext { a, b -> (b - a) / 1000 }.filter { it > 0 }.sorted()
                        val med = gaps.getOrNull(gaps.size / 2)
                        val text = buildString {
                            append("기록 ${pts.size}개 · 상세 샘플 ${series}개")
                            if (mins.isNotEmpty()) append(" · 최저 ${mins.min().roundToInt()}bpm")
                            if (med != null) append(" · 간격 중앙값 ${med / 60}분")
                        }
                        stat(spec, hrDays, pts, { it.getStartLocalDateTime().toLocalDate() }, text)
                    }
                    "energy_score" -> {
                        val pts = readAll(500) { tk ->
                            val b = DataTypes.ENERGY_SCORE.readDataRequestBuilder
                                .setLocalDateFilter(LocalDateFilter.of(startDate, today.plusDays(1)))
                                .setOrdering(Ordering.ASC).setPageSize(100)
                            if (!tk.isNullOrEmpty()) b.setPageToken(tk)
                            b.build()
                        }
                        val vals = pts.mapNotNull { it.getValue(DataType.EnergyScoreType.ENERGY_SCORE) }
                        val text = if (vals.isEmpty()) "값 없음"
                        else "평균 ${vals.average().roundToInt()}점 (최저 ${vals.min().roundToInt()} ~ 최고 ${vals.max().roundToInt()})"
                        stat(spec, windowDays, pts, { it.getStartLocalDateTime().toLocalDate() }, text)
                    }
                    "skin_temperature" -> {
                        val pts = readAll(3000) { tk ->
                            val b = DataTypes.SKIN_TEMPERATURE.readDataRequestBuilder
                                .setInstantTimeFilter(InstantTimeFilter.of(start, end))
                                .setOrdering(Ordering.ASC).setPageSize(200)
                            if (!tk.isNullOrEmpty()) b.setPageToken(tk)
                            b.build()
                        }
                        val vals = pts.mapNotNull { it.getValue(DataType.SkinTemperatureType.SKIN_TEMPERATURE) }
                        stat(spec, windowDays, pts, { it.getStartLocalDateTime().toLocalDate() }, avgText(vals, "°C"))
                    }
                    "blood_oxygen" -> {
                        val pts = readAll(3000) { tk ->
                            val b = DataTypes.BLOOD_OXYGEN.readDataRequestBuilder
                                .setInstantTimeFilter(InstantTimeFilter.of(start, end))
                                .setOrdering(Ordering.ASC).setPageSize(200)
                            if (!tk.isNullOrEmpty()) b.setPageToken(tk)
                            b.build()
                        }
                        val vals = pts.mapNotNull { it.getValue(DataType.BloodOxygenType.OXYGEN_SATURATION) }
                        stat(spec, windowDays, pts, { it.getStartLocalDateTime().toLocalDate() }, avgText(vals, "%", 0))
                    }
                    else -> {
                        // 체성분은 자주 재지 않아서 3배 기간(약 90일)을 본다
                        val bodyDays = windowDays * 3
                        val bodyStart = end.minus(Duration.ofDays(bodyDays.toLong()))
                        val pts = readAll(500) { tk ->
                            val b = DataTypes.BODY_COMPOSITION.readDataRequestBuilder
                                .setInstantTimeFilter(InstantTimeFilter.of(bodyStart, end))
                                .setOrdering(Ordering.ASC).setPageSize(50)
                            if (!tk.isNullOrEmpty()) b.setPageToken(tk)
                            b.build()
                        }
                        val last = pts.lastOrNull()
                        val text = if (last == null) "값 없음" else buildString {
                            last.getValue(DataType.BodyCompositionType.WEIGHT)?.let { append("최근 체중 %.1fkg".format(it)) }
                            last.getValue(DataType.BodyCompositionType.BODY_FAT)?.let { append(" · 체지방 %.1f%%".format(it)) }
                            last.getValue(DataType.BodyCompositionType.BODY_MASS_INDEX)?.let { append(" · BMI %.1f".format(it)) }
                        }.ifEmpty { "측정값 없음" }
                        stat(spec, bodyDays, pts, { it.getStartLocalDateTime().toLocalDate() }, text)
                    }
                }
            } catch (e: Throwable) {
                HealthTypeStat(
                    spec.key, spec.label, true, windowDays, 0, 0, null, null,
                    "읽기 실패", "${e::class.java.simpleName}: ${e.message}",
                )
            }
        }
    }

    /** 개수와 평균 같은 요약만 담은 JSON (원시 기록 없음) */
    fun toJson(stats: List<HealthTypeStat>): String {
        val arr = JSONArray()
        stats.forEach { s ->
            arr.put(JSONObject().apply {
                put("type", s.key)
                put("granted", s.granted)
                put("windowDays", s.windowDays)
                put("points", s.points)
                put("daysWithData", s.days)
                put("firstDate", s.first?.toString() ?: JSONObject.NULL)
                put("lastDate", s.last?.toString() ?: JSONObject.NULL)
                put("summary", s.summary)
                put("error", s.error ?: JSONObject.NULL)
            })
        }
        return JSONObject().apply {
            put("generatedAt", Instant.now().toString())
            put("types", arr)
        }.toString(2)
    }
}
