package io.github.jihoon0501.runcoach

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.reflect.KClass

/**
 * Google Health Connect에서 러닝·걷기·수면·심박을 읽는다 (읽기 전용).
 * 가민 커넥트, 삼성 헬스, 핏빗 등 Health Connect에 기록을 넣는 앱의 데이터를 같은 방식으로 가져올 수 있다.
 * 앱마다 넣어 주는 항목이 달라서, 있는 데이터만 쓰고 없는 항목(케이던스 등)은 비워 둔다.
 */
class HealthConnectRepo(context: Context) : RunSource {

    private val appContext = context.applicationContext

    companion object {
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

        /** 30일보다 오래된 기록까지 읽으려면 필요한 권한 */
        const val HISTORY_PERMISSION = "android.permission.health.READ_HEALTH_DATA_HISTORY"

        private val EXERCISE_READ = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
        private val HR_READ = HealthPermission.getReadPermission(HeartRateRecord::class)
        private val SPEED_READ = HealthPermission.getReadPermission(SpeedRecord::class)
        private val DIST_READ = HealthPermission.getReadPermission(DistanceRecord::class)
        private val STEPS_READ = HealthPermission.getReadPermission(StepsRecord::class)
        private val SLEEP_READ = HealthPermission.getReadPermission(SleepSessionRecord::class)

        private val WEIGHT_READ = HealthPermission.getReadPermission(WeightRecord::class)
        private val HEIGHT_READ = HealthPermission.getReadPermission(HeightRecord::class)
        private val FAT_READ = HealthPermission.getReadPermission(BodyFatRecord::class)

        /** 신체·체성분(체중, 키, 체지방)을 읽는 선택 권한. 설정 탭에서 따로 요청한다 */
        val BODY_PERMISSIONS: Set<String> = setOf(WEIGHT_READ, HEIGHT_READ, FAT_READ)

        /** 앱이 요청하는 읽기 권한 전체 (매니페스트에도 같은 항목이 선언돼 있어야 한다) */
        val PERMISSIONS: Set<String> = setOf(
            EXERCISE_READ, HR_READ, SPEED_READ, DIST_READ, STEPS_READ, SLEEP_READ, HISTORY_PERMISSION,
        )

        /** 이 폰에서 Health Connect를 쓸 수 있는지 (안드로이드 14 이상은 기본 탑재, 그 이하는 앱 설치 필요) */
        fun isAvailable(context: Context): Boolean =
            HealthConnectClient.getSdkStatus(context, PROVIDER_PACKAGE) == HealthConnectClient.SDK_AVAILABLE

        /** Health Connect 앱 설치/업데이트 화면을 여는 의도 */
        fun storeIntent(): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding"))
                .setPackage("com.android.vending")
    }

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(appContext) }

    private suspend fun granted(): Set<String> =
        if (isAvailable(appContext)) client.permissionController.getGrantedPermissions() else emptySet()

    override suspend fun hasPermission(): Boolean = granted().contains(EXERCISE_READ)

    /** 컨디션 계산에 쓰는 수면과 심박 읽기 권한이 모두 있는지 */
    suspend fun hasHealthPermission(): Boolean {
        val g = granted()
        return g.contains(SLEEP_READ) && g.contains(HR_READ)
    }

    suspend fun hasBodyPermission(): Boolean = granted().contains(WEIGHT_READ)

    // ───────────── 신체·체성분 ─────────────

    private suspend fun <T : Record> readAll(type: KClass<T>, from: Instant, to: Instant): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                    pageSize = 500,
                    pageToken = token,
                )
            )
            out += resp.records
            token = resp.pageToken
        } while (!token.isNullOrEmpty())
        return out
    }

    /** 체중·체지방은 측정일별 마지막 값, 키는 가장 최근 값. Health Connect에는 BMI가 없어서 체중과 키로 계산한다 */
    suspend fun fetchBody(from: Instant, to: Instant): List<BodyInfo> {
        val g = granted()
        val zone = ZoneId.systemDefault()
        val weights = LinkedHashMap<LocalDate, Float>()
        val fats = LinkedHashMap<LocalDate, Float>()
        var heightCm: Float? = null
        if (g.contains(WEIGHT_READ)) {
            readAll(WeightRecord::class, from, to).sortedBy { it.time }.forEach {
                weights[it.time.atZone(zone).toLocalDate()] = it.weight.inKilograms.toFloat()
            }
        }
        if (g.contains(FAT_READ)) {
            readAll(BodyFatRecord::class, from, to).sortedBy { it.time }.forEach {
                fats[it.time.atZone(zone).toLocalDate()] = it.percentage.value.toFloat()
            }
        }
        if (g.contains(HEIGHT_READ)) {
            heightCm = runCatching {
                readAll(HeightRecord::class, to.minus(Duration.ofDays(365L * 5)), to)
                    .maxByOrNull { it.time }?.height?.inMeters?.times(100.0)?.toFloat()
            }.getOrNull()
        }
        val dates = (weights.keys + fats.keys).distinct().sortedBy { it.toEpochDay() }
        return dates.map { d ->
            val w = weights[d]
            val h = heightCm
            val bmi = if (w != null && h != null && h > 100f) w / ((h / 100f) * (h / 100f)) else null
            BodyInfo(d, w, h, bmi, fats[d])
        }
    }

    // ───────────── 러닝·걷기 ─────────────

    override suspend fun fetchAll(
        start: Instant,
        end: Instant,
        onChunk: ((List<RunSession>, List<WalkSession>) -> Unit)?,
    ): Pair<List<RunSession>, List<WalkSession>> {
        val g = granted()
        val step = Duration.ofDays(30)
        val runOut = LinkedHashMap<Long, RunSession>()
        val walkOut = LinkedHashMap<Long, WalkSession>()
        var to = end
        while (to.isAfter(start)) {
            val prev = to.minus(step)
            val from = if (prev.isAfter(start)) prev else start
            val (runs, walks) = readWindow(from.minusSeconds(3600), to, g)
            runs.forEach { runOut[it.start.toEpochMilli()] = it }
            walks.forEach { walkOut[it.start.toEpochMilli()] = it }
            onChunk?.invoke(runOut.values.sortedBy { it.start }, walkOut.values.sortedBy { it.start })
            to = from
        }
        return runOut.values.sortedBy { it.start } to walkOut.values.sortedBy { it.start }
    }

    private suspend fun readWindow(from: Instant, to: Instant, g: Set<String>): Pair<List<RunSession>, List<WalkSession>> {
        val sessions = mutableListOf<ExerciseSessionRecord>()
        var token: String? = null
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                    pageSize = 100,
                    pageToken = token,
                )
            )
            sessions += resp.records
            token = resp.pageToken
        } while (!token.isNullOrEmpty())

        // 같은 운동을 여러 앱이 함께 기록한 경우(시작 시각이 2분 안) 하나만 쓴다
        val unique = mutableListOf<ExerciseSessionRecord>()
        for (r in sessions.sortedBy { it.startTime }) {
            val dup = unique.any {
                it.exerciseType == r.exerciseType && Math.abs(Duration.between(it.startTime, r.startTime).seconds) < 120
            }
            if (!dup) unique += r
        }

        val runs = mutableListOf<RunSession>()
        val walks = mutableListOf<WalkSession>()
        for (r in unique) {
            when (r.exerciseType) {
                ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
                ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> buildRun(r, g)?.let { runs += it }
                ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> buildWalk(r, g)?.let { walks += it }
                else -> Unit
            }
        }
        return runs to walks
    }

    private suspend fun buildRun(rec: ExerciseSessionRecord, g: Set<String>): RunSession? {
        val s = rec.startTime
        val e = rec.endTime
        val durSec = Duration.between(s, e).seconds
        if (durSec < 60) return null
        val origin = setOf(rec.metadata.dataOrigin)
        val range = TimeRangeFilter.between(s, e)

        var distM = 0.0
        var avgHr: Int? = null
        var maxHr: Int? = null
        var steps: Long? = null
        try {
            val metrics = buildSet<AggregateMetric<Any>> {
                if (g.contains(DIST_READ)) add(DistanceRecord.DISTANCE_TOTAL)
                if (g.contains(HR_READ)) {
                    add(HeartRateRecord.BPM_AVG)
                    add(HeartRateRecord.BPM_MAX)
                }
                if (g.contains(STEPS_READ)) add(StepsRecord.COUNT_TOTAL)
            }
            if (metrics.isNotEmpty()) {
                val r = client.aggregate(AggregateRequest(metrics = metrics, timeRangeFilter = range, dataOriginFilter = origin))
                distM = r[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0
                avgHr = r[HeartRateRecord.BPM_AVG]?.toInt()
                maxHr = r[HeartRateRecord.BPM_MAX]?.toInt()
                steps = r[StepsRecord.COUNT_TOTAL]
            }
        } catch (ex: Exception) {
            // 일부 값을 못 읽어도 운동 자체는 보여 준다
        }

        // 속도 시계열이 있으면 구간 분석, 없으면 거리 기록으로 속도를 추정, 그것도 없으면 요약만 쓴다
        var speed: List<Pair<Long, Double>> = emptyList()
        if (g.contains(SPEED_READ)) speed = runCatching { speedSeries(s, e, origin) }.getOrDefault(emptyList())
        if (speed.size < 30 && g.contains(DIST_READ)) {
            speed = runCatching { distanceSpeedSeries(s, e, origin) }.getOrDefault(emptyList())
        }
        val hr = if (g.contains(HR_READ)) runCatching { hrSeries(s, e, origin) }.getOrDefault(emptyList()) else emptyList()
        val a = RunAnalyzer.analyze(speed, hr)

        val km = if (distM > 0) distM / 1000.0 else a.segments.sumOf { it.distanceKm }
        if (km < 0.1) return null
        val cadence = steps?.let { (it / (durSec / 60.0)).roundToInt() }?.takeIf { it in 100..230 }
        return RunSession(
            start = s,
            durationSec = durSec,
            distanceKm = km,
            avgHr = a.avgHr ?: avgHr,
            maxHr = maxHr,
            cadence = cadence,
            lapCount = 0,
            title = rec.title,
            steps = steps,
            splits = a.splits,
            mainKm = a.mainKm,
            mainSec = a.mainSec,
            vo2Max = null,
            segments = a.segments,
        )
    }

    private suspend fun buildWalk(rec: ExerciseSessionRecord, g: Set<String>): WalkSession? {
        val s = rec.startTime
        val e = rec.endTime
        val durSec = Duration.between(s, e).seconds
        if (durSec < 300 || !g.contains(DIST_READ)) return null
        var distM = 0.0
        var avgHr: Int? = null
        try {
            val metrics = buildSet<AggregateMetric<Any>> {
                add(DistanceRecord.DISTANCE_TOTAL)
                if (g.contains(HR_READ)) add(HeartRateRecord.BPM_AVG)
            }
            val r = client.aggregate(
                AggregateRequest(
                    metrics = metrics,
                    timeRangeFilter = TimeRangeFilter.between(s, e),
                    dataOriginFilter = setOf(rec.metadata.dataOrigin),
                )
            )
            distM = r[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0
            avgHr = r[HeartRateRecord.BPM_AVG]?.toInt()
        } catch (ex: Exception) {
            return null
        }
        val km = distM / 1000.0
        if (km < 0.3) return null
        return WalkSession(s, durSec, km, avgHr)
    }

    private suspend fun speedSeries(s: Instant, e: Instant, origin: Set<DataOrigin>): List<Pair<Long, Double>> {
        val out = mutableListOf<Pair<Long, Double>>()
        var token: String? = null
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(
                    recordType = SpeedRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(s, e),
                    dataOriginFilter = origin,
                    pageSize = 5000,
                    pageToken = token,
                )
            )
            resp.records.forEach { r -> r.samples.forEach { out += it.time.toEpochMilli() to it.speed.inMetersPerSecond } }
            token = resp.pageToken
        } while (!token.isNullOrEmpty())
        return out.filter { it.first >= s.toEpochMilli() && it.first <= e.toEpochMilli() }.sortedBy { it.first }
    }

    /** 거리 기록(구간별 이동 거리)에서 평균 속도를 계산해 시계열로 만든다 */
    private suspend fun distanceSpeedSeries(s: Instant, e: Instant, origin: Set<DataOrigin>): List<Pair<Long, Double>> {
        val out = mutableListOf<Pair<Long, Double>>()
        var token: String? = null
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(
                    recordType = DistanceRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(s, e),
                    dataOriginFilter = origin,
                    pageSize = 5000,
                    pageToken = token,
                )
            )
            resp.records.forEach { r ->
                val sec = Duration.between(r.startTime, r.endTime).seconds
                if (sec in 1..120) {
                    val v = r.distance.inMeters / sec
                    out += (r.startTime.toEpochMilli() + r.endTime.toEpochMilli()) / 2 to v
                }
            }
            token = resp.pageToken
        } while (!token.isNullOrEmpty())
        return out.sortedBy { it.first }
    }

    private suspend fun hrSeries(s: Instant, e: Instant, origin: Set<DataOrigin>): List<Pair<Long, Double>> {
        val out = mutableListOf<Pair<Long, Double>>()
        var token: String? = null
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(s, e),
                    dataOriginFilter = origin,
                    pageSize = 5000,
                    pageToken = token,
                )
            )
            resp.records.forEach { r -> r.samples.forEach { out += it.time.toEpochMilli() to it.beatsPerMinute.toDouble() } }
            token = resp.pageToken
        } while (!token.isNullOrEmpty())
        return out.sortedBy { it.first }
    }

    // ───────────── 수면·심박 (오늘 컨디션용) ─────────────

    /** 일어난 날짜별 수면 시간과 자는 동안 가장 낮은 심박. Health Connect에는 에너지 점수·피부 온도가 없어서 비워 둔다 */
    suspend fun fetchDays(startDate: LocalDate, endDate: LocalDate): List<DayHealth> {
        val g = granted()
        if (!g.contains(SLEEP_READ)) return emptyList()
        val zone = ZoneId.systemDefault()
        val from = startDate.minusDays(1).atTime(12, 0).atZone(zone).toInstant()
        val to = endDate.plusDays(1).atStartOfDay(zone).toInstant()

        val sleeps = mutableListOf<SleepSessionRecord>()
        var token: String? = null
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                    pageSize = 100,
                    pageToken = token,
                )
            )
            sleeps += resp.records
            token = resp.pageToken
        } while (!token.isNullOrEmpty())

        val asleepStages = setOf(
            SleepSessionRecord.STAGE_TYPE_SLEEPING,
            SleepSessionRecord.STAGE_TYPE_LIGHT,
            SleepSessionRecord.STAGE_TYPE_DEEP,
            SleepSessionRecord.STAGE_TYPE_REM,
        )
        fun minutesOf(r: SleepSessionRecord): Int {
            if (r.stages.isEmpty()) return (Duration.between(r.startTime, r.endTime).seconds / 60).toInt()
            return (r.stages.filter { it.stage in asleepStages }.sumOf { Duration.between(it.startTime, it.endTime).seconds } / 60).toInt()
        }

        val byWake = sleeps.groupBy { it.endTime.atZone(zone).toLocalDate() }
        val out = mutableListOf<DayHealth>()
        var d = startDate
        while (!d.isAfter(endDate)) {
            val ns = byWake[d]
            if (!ns.isNullOrEmpty()) {
                val sleepMin = ns.sumOf { minutesOf(it) }.takeIf { it > 0 }
                var nightHr: Int? = null
                if (g.contains(HR_READ)) {
                    val vals = mutableListOf<Double>()
                    for (n in ns) {
                        runCatching { hrSeries(n.startTime, n.endTime, emptySet()) }.getOrDefault(emptyList())
                            .forEach { if (it.second >= 30.0) vals += it.second }
                    }
                    vals.sort()
                    nightHr = when {
                        vals.size >= 3 -> vals.take(3).average().roundToInt()
                        vals.isNotEmpty() -> vals.first().roundToInt()
                        else -> null
                    }
                }
                if (sleepMin != null || nightHr != null) out += DayHealth(d, sleepMin, null, null, nightHr, null)
            }
            d = d.plusDays(1)
        }
        return out
    }
}

/**
 * Health Connect의 '권한 사용 이유' 화면. Health Connect 앱에서 이 앱의 개인정보처리방침 링크를 누르면 열린다.
 * 방침 웹페이지를 브라우저로 열고 바로 닫는다.
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PRIVACY_POLICY_URL.isNotBlank()) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
        }
        finish()
    }
}
