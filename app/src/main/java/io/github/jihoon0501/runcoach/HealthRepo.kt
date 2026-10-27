package io.github.jihoon0501.runcoach

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

class HealthRepo(private val context: Context) {

    val permissions = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(SpeedRecord::class),
        HealthPermission.getReadPermission(StepsCadenceRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
    )

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun hasPermissions(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(permissions)

    /** 최근 [days]일 러닝 세션 (오래된 순) */
    suspend fun loadRuns(days: Long = 28): List<RunSession> {
        val end = Instant.now()
        val start = end.minus(Duration.ofDays(days))

        val sessions = mutableListOf<ExerciseSessionRecord>()
        var token: String? = null
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageToken = token,
                )
            )
            sessions += resp.records
            token = resp.pageToken
        } while (!token.isNullOrEmpty())

        val runningTypes = setOf(
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
        )

        return sessions
            .filter { it.exerciseType in runningTypes }
            .sortedBy { it.startTime }
            .map { s ->
                val agg = client.aggregate(
                    AggregateRequest(
                        metrics = setOf(
                            DistanceRecord.DISTANCE_TOTAL,
                            HeartRateRecord.BPM_AVG,
                            HeartRateRecord.BPM_MAX,
                            StepsCadenceRecord.RATE_AVG,
                        ),
                        timeRangeFilter = TimeRangeFilter.between(s.startTime, s.endTime),
                    )
                )
                RunSession(
                    start = s.startTime,
                    durationSec = Duration.between(s.startTime, s.endTime).seconds,
                    distanceKm = (agg[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0) / 1000.0,
                    avgHr = agg[HeartRateRecord.BPM_AVG]?.toInt(),
                    maxHr = agg[HeartRateRecord.BPM_MAX]?.toInt(),
                    cadence = agg[StepsCadenceRecord.RATE_AVG]?.toInt(),
                    lapCount = s.laps.size,
                    title = s.title,
                )
            }
    }

    /** 어떤 데이터가 실제로 읽히는지 확인용 JSON */
    fun toDiagnosticJson(runs: List<RunSession>): String {
        val arr = JSONArray()
        runs.forEach { r ->
            arr.put(JSONObject().apply {
                put("start", r.start.toString())
                put("durationSec", r.durationSec)
                put("distanceKm", Math.round(r.distanceKm * 100) / 100.0)
                put("paceSecPerKm", Math.round(r.paceSecPerKm))
                put("avgHr", r.avgHr ?: JSONObject.NULL)
                put("maxHr", r.maxHr ?: JSONObject.NULL)
                put("cadence", r.cadence ?: JSONObject.NULL)
                put("lapCount", r.lapCount)
                put("title", r.title ?: JSONObject.NULL)
            })
        }
        return JSONObject().apply {
            put("generatedAt", Instant.now().toString())
            put("runs", arr)
        }.toString(2)
    }
}
