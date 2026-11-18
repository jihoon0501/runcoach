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

// ═══════════════════════════════════════════════════════════════
// 크로스 트레이닝
// ═══════════════════════════════════════════════════════════════

enum class CrossCategory(val label: String) {
    CARDIO("유산소"), STRENGTH("근력"), SPORT("구기·스포츠"), RECOVERY("회복"), OTHER("기타")
}

enum class ActivityType(
    val label: String,
    val emoji: String,
    val category: CrossCategory,
    val samsungType: String? = null,   // 삼성 헬스 PredefinedExerciseType 상수명 (null=수동 전용)
    val hasDistance: Boolean = false,  // 거리 입력
    val hasReps: Boolean = false,      // 세트/횟수 입력
    val defaultMin: Int = 30,          // 빠른 입력의 기본 운동 시간(분)
    val load: Double = 0.0,            // 러닝 이지런 1분 대비 부하 비율 = (MET ÷ 9.0) × 충격 보정. 0이면 부하에 반영하지 않음
) {
    // ── 유산소 ──
    WALKING       ("걷기",        "🚶", CrossCategory.CARDIO,   "WALKING",                hasDistance = true, defaultMin = 40, load = 0.0),
    TREADMILL     ("러닝머신",    "🏃", CrossCategory.CARDIO,   "TREADMILL",              hasDistance = true, defaultMin = 30, load = 1.0),
    CYCLING       ("사이클",      "🚴", CrossCategory.CARDIO,   "BIKING",                 hasDistance = true, defaultMin = 60, load = 0.26),
    INDOOR_CYCLE  ("실내자전거",  "🚲", CrossCategory.CARDIO,   "STATIONARY_BIKING",      defaultMin = 40, load = 0.26),
    HIKING        ("등산",        "⛰️", CrossCategory.CARDIO,   "HIKING",                 hasDistance = true, defaultMin = 120, load = 0.33),
    SWIMMING      ("수영",        "🏊", CrossCategory.CARDIO,   "POOL_SWIMMING",          defaultMin = 40, load = 0.23),
    ROWING        ("로잉머신",    "🚣", CrossCategory.CARDIO,   "ROWING_MACHINE",         defaultMin = 20, load = 0.23),
    ELLIPTICAL    ("일립티컬",    "🔄", CrossCategory.CARDIO,   "ELLIPTICAL",             defaultMin = 30, load = 0.19),
    STAIR_MACHINE ("스텝머신",    "🪜", CrossCategory.CARDIO,   "STAIR_CLIMBING_MACHINE", defaultMin = 20, load = 0.5),
    JUMP_ROPE     ("줄넘기",      "🪢", CrossCategory.CARDIO,   "JUMP_ROPE",              hasReps = true, defaultMin = 15, load = 0.92),
    HIIT          ("HIIT",        "⚡", CrossCategory.CARDIO,   null,                     defaultMin = 20, load = 0.62),
    DANCE         ("댄스",        "💃", CrossCategory.CARDIO,   null,                     defaultMin = 40, load = 0.33),
    BOXING        ("복싱",        "🥊", CrossCategory.CARDIO,   null,                     defaultMin = 40, load = 0.39),
    SKATING       ("스케이트",    "⛸️", CrossCategory.CARDIO,   null,                     defaultMin = 40, load = 0.42),
    // ── 근력 ──
    SQUAT         ("스쿼트",      "💪", CrossCategory.STRENGTH, "SQUATS",                 hasReps = true, defaultMin = 10, load = 0.21),
    LUNGE         ("런지",        "🦵", CrossCategory.STRENGTH, "LUNGES",                 hasReps = true, defaultMin = 10, load = 0.21),
    PUSH_UP       ("푸시업",      "🤸", CrossCategory.STRENGTH, "PUSH_UPS",               hasReps = true, defaultMin = 10, load = 0.11),
    PULL_UP       ("풀업",        "🏋️", CrossCategory.STRENGTH, "PULL_UPS",               hasReps = true, defaultMin = 10, load = 0.11),
    PLANK         ("플랭크",      "⬛", CrossCategory.STRENGTH, "PLANK",                  defaultMin = 5, load = 0.11),
    BURPEE        ("버피",        "🔥", CrossCategory.STRENGTH, "BURPEES",                hasReps = true, defaultMin = 10, load = 0.62),
    SIT_UP        ("싯업",        "🫃", CrossCategory.STRENGTH, "SIT_UPS",                hasReps = true, defaultMin = 10, load = 0.11),
    CORE          ("코어",        "🎯", CrossCategory.STRENGTH, null,                     defaultMin = 15, load = 0.11),
    GLUTE_BRIDGE  ("브릿지",      "🌉", CrossCategory.STRENGTH, null,                     hasReps = true, defaultMin = 10, load = 0.19),
    CALF_RAISE    ("카프레이즈",  "🦶", CrossCategory.STRENGTH, null,                     hasReps = true, defaultMin = 10, load = 0.19),
    WEIGHT        ("웨이트",      "🏋️", CrossCategory.STRENGTH, "WEIGHT_MACHINE",         defaultMin = 50, load = 0.25),
    KETTLEBELL    ("케틀벨",      "🔔", CrossCategory.STRENGTH, null,                     defaultMin = 30, load = 0.28),
    CIRCUIT       ("서킷",        "🔁", CrossCategory.STRENGTH, null,                     defaultMin = 30, load = 0.62),
    // ── 구기·스포츠 ──
    BADMINTON     ("배드민턴",    "🏸", CrossCategory.SPORT,    "BADMINTON",              defaultMin = 60, load = 0.39),
    TENNIS        ("테니스",      "🎾", CrossCategory.SPORT,    "TENNIS",                 defaultMin = 60, load = 0.57),
    TABLE_TENNIS  ("탁구",        "🏓", CrossCategory.SPORT,    "TABLE_TENNIS",           defaultMin = 40, load = 0.22),
    SOCCER        ("축구",        "⚽", CrossCategory.SPORT,    null,                     defaultMin = 90, load = 0.54),
    BASKETBALL    ("농구",        "🏀", CrossCategory.SPORT,    null,                     defaultMin = 60, load = 0.51),
    VOLLEYBALL    ("배구",        "🏐", CrossCategory.SPORT,    "VOLLEYBALL",             defaultMin = 60, load = 0.31),
    CLIMBING      ("클라이밍",    "🧗", CrossCategory.SPORT,    null,                     defaultMin = 60, load = 0.42),
    GOLF          ("골프",        "⛳", CrossCategory.SPORT,    null,                     defaultMin = 180, load = 0.17),
    SKI           ("스키·보드",   "⛷️", CrossCategory.SPORT,    null,                     defaultMin = 120, load = 0.47),
    // ── 회복 ──
    STRETCHING    ("스트레칭",    "🧘", CrossCategory.RECOVERY, "STRETCHING",             defaultMin = 15, load = 0.0),
    YOGA          ("요가",        "🕉️", CrossCategory.RECOVERY, "YOGA",                   defaultMin = 40, load = 0.07),
    PILATES       ("필라테스",    "🩰", CrossCategory.RECOVERY, "PILATES",                defaultMin = 50, load = 0.08),
    FOAM_ROLL     ("폼롤러",      "🌀", CrossCategory.RECOVERY, null,                     defaultMin = 10, load = 0.0),
    MASSAGE       ("마사지",      "💆", CrossCategory.RECOVERY, null,                     defaultMin = 30, load = 0.0),
    // ── 기타 ──
    CUSTOM        ("기타",        "✏️", CrossCategory.OTHER,    null, load = 0.28),
}

data class ActivitySession(
    val type: ActivityType,
    val start: java.time.Instant,
    val durationSec: Long,
    val distanceKm: Double = 0.0,
    val calories: Int? = null,
    val avgHr: Int? = null,
    val sets: Int = 0,
    val reps: Int = 0,
    val note: String = "",
    val source: String = "manual",   // "manual" | "samsung" | "hc"
) {
    val date: java.time.LocalDate
        get() = start.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val durationMin: Int get() = (durationSec / 60).toInt()
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
    val stage: Int = 0,                        // 점진 상승 단계(0~5). 다음 계획이 한 단계씩만 오르게 기억해 둔다
    val adj: String = "",                      // 컨디션을 반영해 낮춘 훈련의 원본 기록(되돌리기용). "KEEP"이면 원래대로 유지하기로 한 훈련
    val paceAdj: Int = 0,                      // 소화한 훈련 결과로 보정한 목표 페이스(초/km, 음수=빠르게). 같은 결과를 두 번 반영하지 않으려고 기록한다
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

/** GPS 경로 포인트 하나. 삼성 헬스 ExerciseLocation에서 읽어온 값 */
data class RoutePoint(
    val lat: Double,
    val lng: Double,
    val altitudeM: Double = 0.0,
    val accuracyM: Float = 0f,
    val timestampMs: Long = 0L,
    val hr: Int? = null,          // 심박수 (bpm)
    val cadence: Int? = null,     // 케이던스 (spm)
    val speedMps: Double? = null, // 속도 (m/s) → 페이스 환산용
)

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
    val route: List<RoutePoint> = emptyList(),         // GPS 경로 포인트 목록
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
