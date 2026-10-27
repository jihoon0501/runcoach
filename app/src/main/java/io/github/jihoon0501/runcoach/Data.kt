package io.github.jihoon0501.runcoach

import androidx.compose.ui.graphics.Color
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class WorkoutType(val label: String, val emoji: String, val color: Color) {
    INTERVAL("인터벌", "⚡", RunColors.Pink),
    TEMPO("템포", "🔥", RunColors.Orange),
    EASY("이지런", "🌿", RunColors.Green),
    LONG("롱런", "🏔️", RunColors.Blue),
    TEST("기록 측정", "🏁", RunColors.Lime),
}

data class PlanItem(
    val date: LocalDate,
    val week: String,
    val type: WorkoutType,
    val title: String,
    val km: Double,
    val optional: Boolean,
    val tips: List<String>,
    val id: String = "p-$date-${type.name}",   // 날짜를 바꿔도 id는 유지됨(copy)
    val targetPace: Int = 0,                   // 초/km, 0이면 유형별 기본값 사용
    val reps: Int = 0,                         // 인터벌 반복 횟수
    val rescheduled: Boolean = false,
)

enum class SegRole(val label: String) {
    WARMUP("준비운동"), MAIN("본 운동"), RECOVERY("회복"), COOLDOWN("마무리")
}

enum class SegKind(val label: String) {
    REST("정지"), WALK("걷기"), JOG("조깅"), RUN("달리기")
}

/** 운동 중 속도로 나눈 구간 (걷기/조깅/달리기 + 준비운동/회복/마무리 역할) */
data class RunSegment(
    val role: SegRole,
    val kind: SegKind,
    val startSec: Long,
    val durSec: Long,
    val distanceKm: Double,
    val avgHr: Int?,
    val cadence: Int?,
) {
    val paceSecPerKm: Double get() = if (kind == SegKind.REST || distanceKm <= 0.02) 0.0 else durSec / distanceKm
    val label: String
        get() = if (kind == SegKind.REST) "일시정지" else when (role) {
            SegRole.MAIN -> "달리기"
            SegRole.WARMUP -> "준비운동 ${kind.label}"
            SegRole.COOLDOWN -> "마무리 ${kind.label}"
            SegRole.RECOVERY -> "회복 ${kind.label}"
        }
}

/** 걷기 운동 요약. 러닝 기록과는 따로 보관하고, 피로도·컨디션 계산과 회복 걷기 표시에만 쓴다. */
data class WalkSession(
    val start: Instant,
    val durationSec: Long,
    val distanceKm: Double,
    val avgHr: Int?,
) {
    val date: LocalDate get() = start.atZone(ZoneId.systemDefault()).toLocalDate()
}

data class RunSession(
    val start: Instant,
    val durationSec: Long,
    val distanceKm: Double,
    val avgHr: Int?,
    val maxHr: Int?,
    val cadence: Int?,
    val lapCount: Int,
    val title: String?,
    val steps: Long? = null,
    val splits: List<Int> = emptyList(),   // 본 운동 1km 구간 시간(초)
    val mainKm: Double = 0.0,              // 준비/마무리 걷기를 뺀 본 운동 거리
    val mainSec: Long = 0,                 // 본 운동 시간
    val vo2Max: Float? = null,
    val segments: List<RunSegment> = emptyList(),   // 걷기/조깅/달리기 구간 전체
) {
    val date: LocalDate get() = start.atZone(ZoneId.systemDefault()).toLocalDate()
    val paceSecPerKm: Double
        get() = when {
            mainKm > 0.5 && mainSec > 0 -> mainSec / mainKm
            distanceKm > 0 -> durationSec / distanceKm
            else -> 0.0
        }
}

fun formatPace(secPerKm: Double): String {
    if (secPerKm <= 0 || secPerKm.isNaN() || secPerKm.isInfinite()) return "-"
    val total = Math.round(secPerKm).toInt()
    return "%d'%02d\"".format(total / 60, total % 60)
}

fun formatDuration(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun korDow(d: DayOfWeek): String = arrayOf("월", "화", "수", "목", "금", "토", "일")[d.value - 1]

fun dateLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "오늘"
    today.plusDays(1) -> "내일"
    else -> "${date.monthValue}월 ${date.dayOfMonth}일 (${korDow(date.dayOfWeek)})"
}
