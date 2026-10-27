package io.github.jihoon0501.runcoach

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** 하루치 건강 요약 (원시 기록은 저장하지 않는다) */
data class DayHealth(
    val date: LocalDate,
    val sleepMin: Int?,       // 이 날 아침에 일어난 수면의 합계(분)
    val sleepScore: Int?,     // 삼성 수면 점수
    val energy: Int?,         // 삼성 에너지 점수
    val nightHr: Int?,        // 자는 동안 가장 낮은 구간의 평균 심박 (안정 시 심박 대용)
    val skinTemp: Float?,     // 수면 중 평균 피부 온도
)

/**
 * 삼성 헬스에서 수면, 심박, 에너지 점수, 피부 온도를 읽어 하루 단위 요약으로 만들고 폰에 저장한다.
 * 처음엔 최근 35일을 읽고, 이후에는 마지막 동기화 3일 전부터만 다시 읽어서 합친다.
 */
class HealthDaily(context: Context) {

    companion object {
        const val CACHE_VERSION = 1
        private const val FULL_DAYS = 35L
        private const val OVERLAP_DAYS = 3L
        private const val KEEP_DAYS = 60L
    }

    private val appContext = context.applicationContext
    private val store: HealthDataStore by lazy { HealthDataService.getStore(appContext) }
    private val file = File(appContext.filesDir, "health_cache.json")

    private val perms = listOf(
        DataTypes.SLEEP, DataTypes.HEART_RATE, DataTypes.ENERGY_SCORE, DataTypes.SKIN_TEMPERATURE,
    ).map { Permission.of(it, AccessType.READ) }.toSet()

    /** 앱이 실제로 쓰는 건강 데이터 종류 수 (수면, 심박, 에너지 점수, 피부 온도) */
    val total: Int get() = perms.size

    /** 앱이 쓰는 4가지 읽기 권한만 요청하고, 허용된 종류 수를 돌려준다 */
    suspend fun request(activity: android.app.Activity): Int {
        val granted = store.requestPermissions(perms, activity).map { it.dataType.name }.toSet()
        return listOf(DataTypes.SLEEP, DataTypes.HEART_RATE, DataTypes.ENERGY_SCORE, DataTypes.SKIN_TEMPERATURE)
            .count { it.name in granted }
    }

    /** 필요한 4가지 읽기 권한이 모두 허용돼 있는지 */
    suspend fun hasPermission(): Boolean = store.getGrantedPermissions(perms).size >= perms.size

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

    private class Night(val wake: LocalDate, val start: Instant, val end: Instant, val minutes: Int, val score: Int?)

    /** [startDate]~[endDate] 날짜별 요약을 읽는다 */
    suspend fun fetch(startDate: LocalDate, endDate: LocalDate): List<DayHealth> {
        val zone = ZoneId.systemDefault()
        // 전날 정오부터 읽어서, 밤에 시작한 수면이 빠지지 않게 한다
        val from = startDate.minusDays(1).atTime(12, 0).atZone(zone).toInstant()
        val to = endDate.plusDays(1).atStartOfDay(zone).toInstant()

        // ── 수면: 일어난 날짜별로 합산 ──
        val sleepPts = readAll(2000) { tk ->
            val b = DataTypes.SLEEP.readDataRequestBuilder
                .setInstantTimeFilter(InstantTimeFilter.of(from, to))
                .setOrdering(Ordering.ASC).setPageSize(50)
            if (!tk.isNullOrEmpty()) b.setPageToken(tk)
            b.build()
        }
        val nights = sleepPts.map { p ->
            val durSec = p.getValue(DataType.SleepType.DURATION)?.seconds ?: 0L
            val st = p.startTime
            val en = p.endTime ?: st.plusSeconds(durSec)
            val wake = (p.getEndLocalDateTime() ?: p.getStartLocalDateTime()).toLocalDate()
            Night(wake, st, en, (durSec / 60).toInt(), p.getValue(DataType.SleepType.SLEEP_SCORE))
        }
        val byWake = nights.groupBy { it.wake }

        // ── 심박: 수면 구간 안의 가장 낮은 값들 ──
        val hrPts = readAll(9000) { tk ->
            val b = DataTypes.HEART_RATE.readDataRequestBuilder
                .setInstantTimeFilter(InstantTimeFilter.of(from, to))
                .setOrdering(Ordering.ASC).setPageSize(200)
            if (!tk.isNullOrEmpty()) b.setPageToken(tk)
            b.build()
        }
        val hr = hrPts.mapNotNull { p ->
            val v = p.getValue(DataType.HeartRateType.MIN_HEART_RATE) ?: p.getValue(DataType.HeartRateType.HEART_RATE)
            if (v == null || v < 30f) null else p.startTime to v
        }

        // ── 에너지 점수 ──
        val energyPts = readAll(500) { tk ->
            val b = DataTypes.ENERGY_SCORE.readDataRequestBuilder
                .setLocalDateFilter(LocalDateFilter.of(startDate, endDate.plusDays(1)))
                .setOrdering(Ordering.ASC).setPageSize(100)
            if (!tk.isNullOrEmpty()) b.setPageToken(tk)
            b.build()
        }
        val energyBy = energyPts.mapNotNull { p ->
            p.getValue(DataType.EnergyScoreType.ENERGY_SCORE)?.let { p.getStartLocalDateTime().toLocalDate() to it.roundToInt() }
        }.toMap()

        // ── 피부 온도 ──
        val skinPts = readAll(3000) { tk ->
            val b = DataTypes.SKIN_TEMPERATURE.readDataRequestBuilder
                .setInstantTimeFilter(InstantTimeFilter.of(from, to))
                .setOrdering(Ordering.ASC).setPageSize(200)
            if (!tk.isNullOrEmpty()) b.setPageToken(tk)
            b.build()
        }
        val skinBy = skinPts.mapNotNull { p ->
            p.getValue(DataType.SkinTemperatureType.SKIN_TEMPERATURE)?.let {
                (p.getEndLocalDateTime() ?: p.getStartLocalDateTime()).toLocalDate() to it
            }
        }.groupBy({ it.first }, { it.second }).mapValues { e -> e.value.average().toFloat() }

        // ── 날짜별로 합치기 ──
        val out = mutableListOf<DayHealth>()
        var d = startDate
        while (!d.isAfter(endDate)) {
            val ns = byWake[d]
            val sleepMin = ns?.sumOf { it.minutes }?.takeIf { it > 0 }
            val sleepScore = ns?.mapNotNull { it.score }?.maxOrNull()
            val nightHr = if (ns.isNullOrEmpty()) null else {
                val vals = hr.filter { (t, _) -> ns.any { n -> !t.isBefore(n.start) && !t.isAfter(n.end) } }
                    .map { it.second }.sorted()
                when {
                    vals.size >= 3 -> vals.take(3).average().roundToInt()
                    vals.isNotEmpty() -> vals.first().roundToInt()
                    else -> null
                }
            }
            val day = DayHealth(d, sleepMin, sleepScore, energyBy[d], nightHr, skinBy[d])
            if (day.sleepMin != null || day.energy != null || day.nightHr != null || day.skinTemp != null) out += day
            d = d.plusDays(1)
        }
        return out
    }

    // ───────────── 저장 / 동기화 ─────────────

    suspend fun loadCached(): List<DayHealth> = withContext(Dispatchers.IO) { readCache()?.first ?: emptyList() }

    /** 저장본이 없으면 최근 35일을 읽고, 있으면 마지막 동기화 3일 전부터만 읽어서 합친다 */
    suspend fun sync(): List<DayHealth> {
        val today = LocalDate.now()
        val cache = withContext(Dispatchers.IO) { readCache() }
        val list: List<DayHealth> = if (cache == null) {
            fetch(today.minusDays(FULL_DAYS), today)
        } else {
            val syncedDay = LocalDate.ofEpochDay(cache.second)
            val incFrom = maxOf(syncedDay.minusDays(OVERLAP_DAYS), today.minusDays(FULL_DAYS))
            val fresh = fetch(incFrom, today)
            cache.first.filter { it.date < incFrom } + fresh
        }
        val merged = list.distinctBy { it.date }
            .filter { it.date >= today.minusDays(KEEP_DAYS) }
            .sortedBy { it.date }
        withContext(Dispatchers.IO) { writeCache(merged, today.toEpochDay()) }
        return merged
    }

    private fun readCache(): Pair<List<DayHealth>, Long>? {
        if (!file.exists()) return null
        return try {
            val o = JSONObject(file.readText())
            if (o.optInt("version") != CACHE_VERSION) {
                null
            } else {
                val arr = o.getJSONArray("days")
                val days = (0 until arr.length()).map { i ->
                    val j = arr.getJSONObject(i)
                    DayHealth(
                        date = LocalDate.parse(j.getString("date")),
                        sleepMin = if (j.isNull("sleepMin")) null else j.getInt("sleepMin"),
                        sleepScore = if (j.isNull("sleepScore")) null else j.getInt("sleepScore"),
                        energy = if (j.isNull("energy")) null else j.getInt("energy"),
                        nightHr = if (j.isNull("nightHr")) null else j.getInt("nightHr"),
                        skinTemp = if (j.isNull("skinTemp")) null else j.getDouble("skinTemp").toFloat(),
                    )
                }
                days to o.getLong("syncedDay")
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeCache(days: List<DayHealth>, syncedDay: Long) {
        val arr = JSONArray()
        days.forEach { d ->
            arr.put(JSONObject().apply {
                put("date", d.date.toString())
                put("sleepMin", d.sleepMin ?: JSONObject.NULL)
                put("sleepScore", d.sleepScore ?: JSONObject.NULL)
                put("energy", d.energy ?: JSONObject.NULL)
                put("nightHr", d.nightHr ?: JSONObject.NULL)
                put("skinTemp", d.skinTemp?.toDouble() ?: JSONObject.NULL)
            })
        }
        val o = JSONObject().apply {
            put("version", CACHE_VERSION)
            put("syncedDay", syncedDay)
            put("days", arr)
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(o.toString())
            tmp.delete()
        }
    }
}
