package io.github.jihoon0501.runcoach

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 삼성 헬스에서 읽어올 기간 (일). 730일 = 약 2년 */
object Config {
    const val LOAD_DAYS = 730L
}

/** 사용자가 첫 실행 화면(또는 설정)에서 입력하는 정보. 폰 안에만 저장된다. */
data class ProfileData(
    val nickname: String = "",
    val birthYear: Int = 0,          // 0 = 입력 안 함
    val sex: String = "",            // "", "M", "F" (선택 입력)
    val heightCm: Int = 170,
    val weightKg: Int = 65,
    val maxHr: Int = 0,              // 0 = 나이로 추정
    val goalDistanceKm: Double = 10.0,
    val goalTimeSec: Int = 0,        // 0 = 목표 기록 없음 (최근 기록을 기준으로 삼는다)
    val raceDate: LocalDate? = null,   // 목표 대회 날짜. null이면 날짜 없이 일반 계획
    val intensityLevel: Int = 0,         // -1 여유롭게, 0 기본, +1 도전적으로
    val seasonAware: Boolean = true,     // 한국 계절성 반영 여부
    val runDays: Int = 4,            // 주당 러닝 횟수 선호 (3 또는 4)
    val weeklyKm: Int = 15,          // 요즘 일주일에 달리는 거리 (기록이 부족할 때 기준)
    val jointCare: Boolean = true,   // 무릎·관절 부담을 줄이는 쪽을 선호
    val onboarded: Boolean = false,
)

/** 앱 전체가 참조하는 현재 사용자 설정. 값은 [ProfileData]에서 오고, 심박 기준 등은 여기서 계산한다. */
object UserProfile {
    var data: ProfileData = ProfileData()

    /** 사용자가 설정에서 켠 경우에만 채워지는 체중 변화량 한 줄. AI 평가 요청에 함께 보낸다 (BodyStore.updateAiContext) */
    @Volatile var bodyTrend: String? = null

    /** 목표 기록이 없을 때, 최근 기록으로 정한 기준 페이스 (10K 환산, 초/km). 0이면 아직 없음 */
    var autoGoalPace: Int = 0

    val NAME: String get() = data.nickname.ifBlank { "러너" }
    val HEIGHT_CM: Int get() = data.heightCm
    val WEIGHT_KG: Int get() = data.weightKg
    val RUN_DAYS: Int get() = data.runDays.coerceIn(3, 7)
    val BASELINE_KM: Int get() = data.weeklyKm.coerceIn(3, 120)

    val age: Int? get() = if (data.birthYear in 1900..LocalDate.now().year) LocalDate.now().year - data.birthYear else null

    val bmi: Double get() = data.weightKg / ((data.heightCm / 100.0) * (data.heightCm / 100.0))

    /** 최대 심박: 입력값이 있으면 그 값, 없으면 나이로 추정(Tanaka: 208 - 0.7 x 나이), 나이도 없으면 185 */
    val MAX_HR: Int get() {
        if (data.maxHr > 0) return data.maxHr
        val a = age ?: return 185
        return (208 - 0.7 * a).roundToInt()
    }

    /** 이지런 심박 상한 (최대 심박의 80%) */
    val EASY_HR: Int get() = (MAX_HR * 0.80).roundToInt()

    /** 강한 러닝으로 보는 심박 (최대 심박의 85%) */
    val HARD_HR: Int get() = (MAX_HR * 0.85).roundToInt()

    /** 목표를 10K 기준 페이스(초/km)로 환산한 값 (Riegel: t2 = t1 x (d2/d1)^1.06). 목표 기록이 없으면 최근 기록 기준 */
    val GOAL_10K_PACE: Int get() {
        val d = data
        if (d.goalTimeSec > 0 && d.goalDistanceKm > 0) {
            val t10 = d.goalTimeSec * Math.pow(10.0 / d.goalDistanceKm, 1.06)
            return (t10 / 10.0).roundToInt().coerceIn(180, 600)
        }
        return if (autoGoalPace > 0) autoGoalPace else 360
    }

    /** 기록이 아직 없을 때 쓰는 이지런 페이스 (목표 10K 페이스보다 약 100초 느리게) */
    val DEFAULT_EASY_PACE: Int get() = GOAL_10K_PACE + 100

    fun goalLabel(): String {
        val d = data
        val name = when {
            d.goalDistanceKm <= 5.1 -> "5K"
            d.goalDistanceKm <= 10.1 -> "10K"
            d.goalDistanceKm <= 21.2 -> "하프마라톤"
            else -> "풀마라톤"
        }
        return if (d.goalTimeSec > 0) "$name ${formatDuration(d.goalTimeSec.toLong())}" else "$name 꾸준히 완주·기록 향상"
    }

    /** AI에게 보내는 사용자 소개 (이름은 보내지 않는다) */
    fun promptSummary(): String = buildString {
        age?.let { append("나이 ${it}세, ") }
        when (data.sex) {
            "M" -> append("남성, ")
            "F" -> append("여성, ")
        }
        append("키 ${data.heightCm}cm, 체중 ${data.weightKg}kg. ")
        append("목표: ${goalLabel()}. 주 ${RUN_DAYS}회 달리는 걸 목표로 함. ")
        append("최대 심박 약 ${MAX_HR}bpm. ")
        if (data.jointCare) append("무릎·관절 부담을 줄이는 쪽을 선호하고, 강한 훈련 사이에 휴식이 필요함.")
        bodyTrend?.let { append("\n").append(it) }
        append("\n\n")
    }
}

data class Evaluation(
    val score: Int,
    val grade: String,
    val summary: String,
    val points: List<String>,
)

data class Achievement(
    val percent: Int,
    val plannedKm: Double,
    val doneKm: Double,
    val doneCount: Int,
    val totalCount: Int,
    val note: String = "",      // 계획 시작 전 기간을 어떤 기준으로 계산했는지 설명
)

/** 새 계획을 만들 때 왜 그렇게 바꿨는지 설명하는 기록 */
data class PlanNote(
    val createdAt: String,         // 만든 시각 (예: 10/24 21:30)
    val score: Int,                // 피로도 0~100
    val level: String,             // 높음 / 보통 / 낮음
    val facts: List<String>,       // 판단 근거 수치
    val changes: List<String>,     // 기존 계획에서 바뀐 점
    val reasons: List<String>,     // 바꾼 이유
    val seasonWarning: String? = null,   // 계절 경고 한 줄 (null이면 없음)
    val seasonWarnLevel: String? = null, // "주의" 또는 "위험"
)

class PlanUpdate(val newPlan: List<PlanItem>, val note: PlanNote, val anchor: LocalDate)

/** 하루 동안의 걷기 합계. recovery=true면 '회복 걷기'로 본다 (30분 이상이고 평균 심박이 낮을 때) */
class WalkDay(val date: LocalDate, val minutes: Int, val km: Double, val avgHr: Int?, val recovery: Boolean)

enum class PlanStatus { DONE, WALKED, MISSED, TODAY, UPCOMING }

object PlanEngine {

    private val QUALITY = setOf(WorkoutType.INTERVAL, WorkoutType.TEMPO, WorkoutType.LONG, WorkoutType.TEST)

    fun targetPace(p: PlanItem): Int = when {
        p.targetPace > 0 -> p.targetPace
        p.type == WorkoutType.INTERVAL -> UserProfile.GOAL_10K_PACE - 5
        p.type == WorkoutType.TEMPO -> UserProfile.GOAL_10K_PACE + 10
        p.type == WorkoutType.TEST -> UserProfile.GOAL_10K_PACE
        else -> UserProfile.DEFAULT_EASY_PACE
    }

    fun repsOf(p: PlanItem): Int {
        if (p.reps > 0) return p.reps
        val m = Regex("×\\s*(\\d+)").find(p.title)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: 5
    }

    // ───────────── 상태 / 매칭 ─────────────

    /** [walked]: 그 날 러닝 대신 회복 걷기를 했는지 (미완료 대신 '회복 걷기'로 인정) */
    fun status(p: PlanItem, done: Boolean, today: LocalDate, walked: Boolean = false): PlanStatus = when {
        done -> PlanStatus.DONE
        p.date < today && walked -> PlanStatus.WALKED
        p.date < today -> PlanStatus.MISSED
        p.date == today -> PlanStatus.TODAY
        else -> PlanStatus.UPCOMING
    }

    /** 계획 id → 그 날 실제로 한 러닝 (계획 거리의 50% 이상이면 인정) */
    fun match(plan: List<PlanItem>, runs: List<RunSession>): Map<String, RunSession> {
        val used = mutableSetOf<Long>()
        val out = mutableMapOf<String, RunSession>()
        for (p in plan.sortedBy { it.date }) {
            // 같은 날 러닝 중 거리가 계획의 30% 이상이면 매칭 (기존 50% → 완화)
            // 거리 조건을 충족 못해도 같은 날 가장 긴 러닝은 매칭 (계획에 없던 러닝 오표기 방지)
            val dayRuns = runs.filter { it.date == p.date && it.start.toEpochMilli() !in used }
            val cand = dayRuns.filter { it.distanceKm >= p.km * 0.3 }.maxByOrNull { it.distanceKm }
                ?: dayRuns.maxByOrNull { it.distanceKm } ?: continue
            used += cand.start.toEpochMilli()
            out[p.id] = cand
        }
        return out
    }

    /**
     * 해당 월 달성률 = 완료한 거리 / 추천 거리.
     * - 계획이 있는 날: 계획 거리 대비 실제 완료 거리 (선택 훈련 제외)
     * - 계획이 시작되기 전 날짜(읽어온 기록 범위 안): 그 기간 끝 시점의 '최근 4주 평균 주간 거리(+5%)'를
     *   기준 목표로 보고, 그 기간에 실제로 달린 거리를 인정한다.
     */
    fun achievement(
        plan: List<PlanItem>,
        matches: Map<String, RunSession>,
        runs: List<RunSession>,
        ym: YearMonth,
        today: LocalDate,
    ): Achievement {
        val items = plan.filter { !it.optional && YearMonth.from(it.date) == ym }
        var planned = items.sumOf { it.km }
        var done = 0.0
        var cnt = 0
        for (p in items) {
            val r = matches[p.id] ?: continue
            done += min(r.distanceKm, p.km)
            cnt++
        }

        var note = ""
        val planStart = plan.minOfOrNull { it.date }
        val coverageStart = today.minusDays(Config.LOAD_DAYS)
        if (runs.isNotEmpty()) {
            val days = (1..ym.lengthOfMonth()).map { ym.atDay(it) }.filter { d ->
                d >= coverageStart && d <= today && (planStart == null || d < planStart)
            }
            if (days.isNotEmpty()) {
                val windowEnd = days.last()
                val windowStart = maxOf(windowEnd.minusDays(27), coverageStart)
                val windowLen = (windowEnd.toEpochDay() - windowStart.toEpochDay() + 1).toInt()
                val windowKm = runs.filter { it.date >= windowStart && it.date <= windowEnd }.sumOf { it.distanceKm }
                val weeklyBase = windowKm / (windowLen / 7.0) * 1.05
                if (weeklyBase > 0) {
                    val daySet = days.toSet()
                    val baseKm = weeklyBase * days.size / 7.0
                    val actualKm = runs.filter { it.date in daySet }.sumOf { it.distanceKm }
                    planned += baseKm
                    done += min(actualKm, baseKm)
                    val f = days.first()
                    val l = days.last()
                    note = "${f.monthValue}/${f.dayOfMonth}~${l.monthValue}/${l.dayOfMonth}은 계획 시작 전이라 " +
                        "그 시점의 최근 4주 평균 주간 거리 ${"%.0f".format(weeklyBase)}km를 기준으로 계산했어요"
                }
            }
        }
        val pct = if (planned > 0) (done / planned * 100).roundToInt().coerceIn(0, 100) else 0
        return Achievement(pct, planned, done, cnt, items.size, note)
    }

    /** 미완료 훈련을 옮길 만한 날짜 추천 (강한 훈련이 연속되지 않게) */
    fun suggestDates(item: PlanItem, plan: List<PlanItem>, today: LocalDate, count: Int = 3): List<LocalDate> {
        val others = plan.filter { it.id != item.id }
        val out = mutableListOf<LocalDate>()
        var d = today
        for (i in 0..21) {
            val taken = others.any { it.date == d }
            if (!taken) {
                val adjacentHard = others.any {
                    it.type in QUALITY && (it.date == d.minusDays(1) || it.date == d.plusDays(1))
                }
                if (!(item.type in QUALITY && adjacentHard)) out += d
            }
            if (out.size >= count) break
            d = d.plusDays(1)
        }
        return out
    }

    // ───────────── 한 달 계획 생성 ─────────────

    private fun easyPaceOf(runs: List<RunSession>): Int {
        val easy = runs
            .filter { (it.avgHr ?: 999) <= UserProfile.EASY_HR + 3 && it.distanceKm >= 5 }
            .map { it.paceSecPerKm }
            .filter { it > 0 }
            .sorted()
        if (easy.isEmpty()) return UserProfile.DEFAULT_EASY_PACE
        val med = easy[easy.size / 2]
        return ((med / 5.0).roundToInt() * 5).coerceIn(340, 400)
    }

    private fun stdev(values: List<Int>): Double {
        if (values.size < 2) return 0.0
        val m = values.average()
        return sqrt(values.sumOf { (it - m) * (it - m) } / values.size)
    }

    fun evaluate(p: PlanItem, r: RunSession): Evaluation {
        val points = mutableListOf<String>()
        val target = targetPace(p)
        val km = r.distanceKm
        val ratio = if (p.km > 0) km / p.km else 1.0
        val distScore = when {
            ratio in 0.9..1.15 -> 100.0
            ratio < 0.9 -> ratio / 0.9 * 100.0
            else -> (100.0 - (ratio - 1.15) * 150.0).coerceAtLeast(60.0)
        }
        points += "거리 %.1f / %.1fkm (%d%%)".format(km, p.km, Math.round(ratio * 100))

        val splits = r.splits
        val pace = r.paceSecPerKm
        var intensity = 100.0
        var extra = ""

        when (p.type) {
            WorkoutType.EASY, WorkoutType.LONG -> {
                val hr = r.avgHr
                if (hr != null) {
                    if (hr > UserProfile.EASY_HR + 3) {
                        intensity -= (hr - (UserProfile.EASY_HR + 3)) * 2.0
                        points += "평균 심박 ${hr}bpm (기준 ${UserProfile.EASY_HR + 3} 초과)"
                    } else points += "평균 심박 ${hr}bpm (안정적)"
                }
                val fast = splits.count { it < target - 30 }
                if (fast > 0) {
                    intensity -= fast * 8.0
                    points += "목표보다 30초 이상 빠른 구간 ${fast}개"
                }
                if (pace > 0 && pace < target - 20) {
                    intensity -= 10.0
                    points += "평균 ${formatPace(pace)} (목표 ${formatPace(target.toDouble())}보다 빠름)"
                } else points += "평균 페이스 ${formatPace(pace)}"
                if (p.type == WorkoutType.LONG && splits.size >= 4) {
                    val sd = stdev(splits)
                    points += "구간 페이스 편차 ±${sd.roundToInt()}초"
                    if (sd > 20) intensity -= 10.0
                }
            }
            WorkoutType.TEMPO -> {
                // 크루즈 템포(구간 사이에 쉬는 조깅)는 달린 구간만 따로 평균을 낸다
                val cruise = if (p.reps > 1) {
                    r.segments.filter { it.role == SegRole.MAIN && it.kind == SegKind.RUN && it.durSec >= 300 }
                } else emptyList()
                if (cruise.size >= 2) {
                    val avgC = cruise.map { it.paceSecPerKm }.filter { it > 0 }.average()
                    val cntC = min(cruise.size, p.reps)
                    points += "템포 구간 ${cruise.size}회 감지 (계획 ${p.reps}회)"
                    points += "구간 평균 ${formatPace(avgC)} (목표 ${formatPace(target.toDouble())})"
                    intensity = cntC.toDouble() / p.reps * 60.0 + (40.0 - abs(avgC - target) * 3.0).coerceIn(0.0, 40.0)
                    extra = if (avgC - target > 8) "목표보다 느렸어요." else if (avgC - target < -8) "목표보다 빨랐어요." else "목표 페이스에 잘 맞았어요."
                } else {
                    val core = if (splits.size > 4) splits.drop(1).dropLast(1) else splits
                    val avg = if (core.isNotEmpty()) core.average() else pace
                    val diff = avg - target
                    intensity -= abs(diff) * 3.0
                    points += "템포 구간 평균 ${formatPace(avg)} (목표 ${formatPace(target.toDouble())})"
                    extra = if (diff > 8) "목표보다 느렸어요." else if (diff < -8) "목표보다 빨랐어요." else "목표 페이스에 잘 맞았어요."
                }
            }
            WorkoutType.INTERVAL -> {
                val reps = repsOf(p)
                // 반복 한 번의 길이(km)를 제목에서 읽는다. 1km 반복은 예전과 같고, 긴 반복(하프·풀)은 예상 시간 기준으로 찾는다
                val repKm = Regex("(\\d+(?:\\.\\d+)?)km×").find(p.title)?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0
                val expSec = (repKm * target).toInt()
                val segLo = if (repKm <= 1.0) 120 else (expSec * 0.5).toInt()
                val segHi = if (repKm <= 1.0) 420 else (expSec * 1.6).toInt()
                val expKm = (reps * repKm).roundToInt().coerceAtLeast(1)
                val segs = r.segments.filter { it.role == SegRole.MAIN && it.kind == SegKind.RUN && it.durSec in segLo..segHi }
                if (segs.size >= 2) {
                    val avg = segs.map { it.paceSecPerKm }.filter { it > 0 }.average()
                    val cnt = min(segs.size, reps)
                    points += "빠른 반복 ${segs.size}회 감지 (계획 ${reps}회)"
                    points += "반복 평균 ${formatPace(avg)} (목표 ${formatPace(target.toDouble())})"
                    intensity = cnt.toDouble() / reps * 60.0 + (40.0 - abs(avg - target) * 2.0).coerceIn(0.0, 40.0)
                } else {
                    val near = splits.count { it <= target + 15 }
                    points += "목표 페이스 근처 구간 ${near}개 (계획 ${expKm}개)"
                    intensity = (min(near, expKm).toDouble() / expKm * 100.0)
                }
            }
            WorkoutType.TEST -> {
                val t = if (r.mainSec > 0) r.mainSec else r.durationSec
                val goalSec = UserProfile.GOAL_10K_PACE * 10
                val diff = t - goalSec
                points += "10K 기록 ${formatDuration(t)} (목표 ${formatDuration(goalSec.toLong())})"
                intensity = (100.0 - diff / 60.0 * 10.0).coerceIn(50.0, 100.0)
                extra = if (diff <= 0) "목표를 달성했어요!" else "목표까지 ${diff}초 남았어요."
            }
        }

        val score = (0.35 * distScore + 0.65 * intensity.coerceIn(0.0, 100.0)).roundToInt().coerceIn(0, 100)
        val grade = when {
            score >= 90 -> "매우 좋음"
            score >= 75 -> "좋음"
            score >= 60 -> "보통"
            else -> "아쉬움"
        }
        val distNote = when {
            ratio < 0.9 -> "거리가 계획보다 짧았어요."
            ratio > 1.15 -> "계획보다 많이 달렸어요."
            else -> "거리는 계획에 잘 맞았어요."
        }
        val summary = "${p.title}: $distNote ${extra}".trim()
        return Evaluation(score, grade, summary, points)
    }

    // ───────────── 최근 부하·피로도를 반영한 재계획 ─────────────

    // ───────────── 걷기 ─────────────

    /** 걷기 1km를 러닝 몇 km의 부하로 볼지 (러닝보다 충격이 훨씬 적어서 25%) */
    const val WALK_LOAD_FACTOR = 0.25
    private const val RECOVERY_WALK_MIN = 30        // 이 시간(분) 이상 걸으면 회복 걷기 후보
    private const val RECOVERY_WALK_MAX_HR = 125    // 평균 심박이 이 값 이하(1~2구간)일 때만 회복 걷기

    // ───────────── 크로스 트레이닝 부하 ─────────────

    /** 크로스 트레이닝 한 번이 러닝 환산으로 아무리 커도 이 값을 넘기지 않는다 (입력 실수·긴 활동 시간 방지) */
    const val CROSS_SESSION_MAX_KM = 12.0

    /**
     * 크로스 트레이닝 한 번의 러닝 환산 거리(km) = 시간(분) × ActivityType.load × (이지런이 1분에 가는 거리).
     * load는 (MET ÷ 9.0) × 충격 보정이다. 러닝머신은 러닝과 같아서 거리가 있으면 그 거리를 그대로 쓴다.
     */
    private fun crossOne(s: ActivitySession, easyPaceSec: Int): Double {
        val km = if (s.type == ActivityType.TREADMILL && s.distanceKm > 0.0) {
            s.distanceKm
        } else {
            s.durationSec / 60.0 * s.type.load * (60.0 / easyPaceSec)
        }
        return km.coerceAtMost(CROSS_SESSION_MAX_KM)
    }

    /** 최근 [days]일 크로스 트레이닝의 러닝 환산 거리 합계. 걷기는 삼성 헬스 걷기와 같은 날이면 이중 계산을 피한다 */
    private fun crossEq(
        cross: List<ActivitySession>, walks: List<WalkSession>, today: LocalDate, days: Int, easyPaceSec: Int,
    ): Double {
        if (cross.isEmpty()) return 0.0
        val from = today.minusDays(days - 1L)
        val walkDates = walks.map { it.date }.toSet()
        return cross.filter { it.date >= from && it.date <= today }.sumOf { s ->
            if (s.type == ActivityType.WALKING) {
                if (s.date in walkDates) 0.0
                else (if (s.distanceKm > 0.0) s.distanceKm else s.durationSec / 3600.0 * 5.0) * WALK_LOAD_FACTOR
            } else {
                crossOne(s, easyPaceSec)
            }
        }
    }

    /** 크로스 트레이닝 한 번의 러닝 환산 거리(km). 화면 표시용이라 걷기는 0으로 둔다 */
    fun crossKm(s: ActivitySession, runs: List<RunSession>): Double {
        if (s.type == ActivityType.WALKING) return 0.0
        val easy = easyPaceOf(runs.filter { it.date >= s.date.minusDays(27) && it.date <= s.date })
        return crossOne(s, easy)
    }

    private fun walkEq(walks: List<WalkSession>, today: LocalDate, days: Int): Double =
        walks.filter { it.date >= today.minusDays(days - 1L) && it.date <= today }.sumOf { it.distanceKm } * WALK_LOAD_FACTOR

    /** 날짜별 걷기 합계 */
    fun walkDays(walks: List<WalkSession>): Map<LocalDate, WalkDay> =
        walks.groupBy { it.date }.mapValues { (d, list) ->
            val minutes = (list.sumOf { it.durationSec } / 60).toInt()
            val km = list.sumOf { it.distanceKm }
            val withHr = list.filter { it.avgHr != null }
            val hrSec = withHr.sumOf { it.durationSec }
            val hr = if (hrSec > 0) (withHr.sumOf { (it.avgHr ?: 0) * it.durationSec } / hrSec).toInt() else null
            WalkDay(d, minutes, km, hr, recovery = minutes >= RECOVERY_WALK_MIN && (hr == null || hr <= RECOVERY_WALK_MAX_HR))
        }

    /** 최근 7일 걷기 요약 (예: 12.3km · 3시간 10분 (러닝 환산 +3.1km)), 거의 없으면 null */
    fun walkSummary(walks: List<WalkSession>, today: LocalDate): String? {
        val w = walks.filter { it.date >= today.minusDays(6) && it.date <= today }
        val km = w.sumOf { it.distanceKm }
        if (km < 0.5) return null
        val min = (w.sumOf { it.durationSec } / 60).toInt()
        val dur = if (min >= 60) "${min / 60}시간 ${min % 60}분" else "${min}분"
        return "${"%.1f".format(km)}km · $dur (러닝 환산 +${"%.1f".format(km * WALK_LOAD_FACTOR)}km)"
    }

    // ───────────── 회복 규칙 ─────────────

    /** 규칙으로 정한 휴식일 수와 그 이유. 새 계획과 AI 평가가 같은 판단을 쓰도록 한 곳에서 계산한다. */
    class RecoveryAdvice(val restDays: Int, val reasons: List<String>)

    fun recoveryAdvice(runs: List<RunSession>, today: LocalDate): RecoveryAdvice {
        val sorted = runs.filter { it.date <= today }.sortedBy { it.start }
        val reasons = mutableListOf<String>()
        var rest = 0
        val kmBase = baseKm(sorted, today)
        val longRun = sorted.filter { it.date >= today.minusDays(2) }.maxByOrNull { it.distanceKm }
        if (longRun != null && longRun.distanceKm >= maxOf(8.0, kmBase * 0.40)) {
            rest = maxOf(rest, 1)
            reasons += "${dayLabel(longRun.date)} ${"%.1f".format(longRun.distanceKm)}km 장거리 뒤라 근육·건 회복이 필요해요"
        }
        val hard = sorted.filter { it.date >= today.minusDays(3) && isHard(it) }
        if (hard.isNotEmpty()) {
            rest = maxOf(rest, 1)
            reasons += "최근 사흘 안에 강한 러닝(${dayLabel(hard.last().date)})이 있었어요"
        }
        val runDays = sorted.filter { it.date >= today.minusDays(5) }.map { it.date }.distinct().size
        if (runDays >= UserProfile.RUN_DAYS) {
            rest = maxOf(rest, 1)
            reasons += "최근 6일 중 ${runDays}일을 달렸어요. 주 ${UserProfile.RUN_DAYS}회 계획이지만 오늘은 회복에 쓰는 것을 추천해요."
        }
        var consecutive = 0
        var cursor = if (sorted.any { it.date == today }) today else today.minusDays(1)
        while (sorted.any { it.date == cursor }) {
            consecutive++
            cursor = cursor.minusDays(1)
        }
        if (consecutive >= 3) {
            rest = maxOf(rest, if (consecutive >= 4) 2 else 1)
            reasons += "${consecutive}일 연속 달렸어요"
        }
        val km7 = sorted.filter { it.date >= today.minusDays(6) }.sumOf { it.distanceKm }
        if (km7 >= maxOf(20.0, kmBase * 1.2)) {
            rest = maxOf(rest, 1)
            reasons += "최근 7일 ${"%.0f".format(km7)}km로 많이 달렸어요"
        }
        return RecoveryAdvice(rest, reasons)
    }

    /** AI 평가에 넘길, 앱이 계산한 회복 판단 문장 */
    fun adviceText(runs: List<RunSession>, day: LocalDate): String {
        val a = recoveryAdvice(runs, day)
        return if (a.restDays > 0) {
            "내일(${dayLabel(day.plusDays(1))})부터 ${a.restDays}일은 러닝 휴식이 필요해요. 이유: ${a.reasons.joinToString("; ")}. " +
                "다음 러닝은 ${dayLabel(day.plusDays(a.restDays + 1L))} 이후, 이지런부터 다시 시작해요."
        } else {
            "휴식이 꼭 필요한 신호는 없어요. 강한 훈련 사이에는 하루 이상 간격을 두세요."
        }
    }

    /** 계획 id가 겹치면(날짜를 옮긴 훈련과 새로 만든 훈련 등) 날짜가 늦은 쪽에 번호를 붙여 구분한다 */
    fun dedupeIds(plan: List<PlanItem>): List<PlanItem> {
        val seen = mutableSetOf<String>()
        return plan.sortedBy { it.date }.map { p ->
            if (seen.add(p.id)) {
                p
            } else {
                var n = 2
                while (!seen.add("${p.id}-$n")) n++
                p.copy(id = "${p.id}-$n")
            }
        }
    }

    /** 평소 주간 거리: 최근 28일 평균(러닝이 충분할 때), 부족하면 사용자가 입력한 값 */
    fun baseKm(runs: List<RunSession>, today: LocalDate): Double {
        val last28 = runs.filter { it.date <= today && it.date >= today.minusDays(27) }
        val chronic = last28.sumOf { it.distanceKm } / 4.0
        return if (last28.size >= 4 && chronic >= 5.0) chronic else UserProfile.BASELINE_KM.toDouble()
    }

    /** 목표 기록이 없을 때: 최근 8주 중 5km 이상 러닝의 가장 빠른 페이스보다 3% 빠르게 (10K 환산 근사) */
    fun autoGoalPace(runs: List<RunSession>, today: LocalDate): Int {
        val best = runs
            .filter { it.date >= today.minusDays(55) && it.date <= today && (if (it.mainKm > 0.5) it.mainKm else it.distanceKm) >= 5.0 }
            .map { it.paceSecPerKm }.filter { it > 0 }.minOrNull() ?: return 0
        return round5(best * 0.97)
    }

    // ───────────── 훈련 주차 (처음 시작한 주부터 계속 누적) ─────────────

    fun mondayOf(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value - 1).toLong())

    /** [anchorMonday]가 속한 주를 1주차로 센 [date]의 주차. 1보다 작으면 시작 전이에요. */
    fun weekNumber(anchorMonday: LocalDate, date: LocalDate): Int =
        (ChronoUnit.WEEKS.between(anchorMonday, mondayOf(date)) + 1).toInt()

    /** 예: "12주차 · 상승" (단계 이름이 없으면 "12주차") */
    fun weekLabel(anchorMonday: LocalDate, date: LocalDate, phase: String?): String {
        val n = weekNumber(anchorMonday, date)
        val head = if (n >= 1) "${n}주차" else "시작 전"
        return if (phase.isNullOrBlank()) head else "$head · $phase"
    }

    /**
     * 모든 훈련의 주차 문구를 날짜 기준으로 다시 붙인다. 같은 주에 단계 이름이 여러 개면 가장 최근 훈련의 이름을 쓴다.
     * 새 계획을 만들어도, 날짜를 옮겨도, 시작일을 바꿔도 주차는 한 가지 기준(처음 시작한 주)으로 이어진다.
     */
    fun relabelWeeks(plan: List<PlanItem>, anchorMonday: LocalDate): List<PlanItem> {
        val phaseOf = plan.sortedBy { it.date }.groupBy { mondayOf(it.date) }.mapValues { (_, items) ->
            items.mapNotNull { p -> p.week.substringAfter("·", "").trim().ifEmpty { null } }.lastOrNull()
        }
        return plan.map { p -> p.copy(week = weekLabel(anchorMonday, p.date, phaseOf[mondayOf(p.date)])) }
    }

    private fun isHard(r: RunSession): Boolean {
        val pace = r.paceSecPerKm
        return r.distanceKm >= 4.0 && ((r.avgHr ?: 0) >= UserProfile.HARD_HR || (pace > 0 && pace <= UserProfile.GOAL_10K_PACE + 25.0))
    }

    /** 러닝 기록만으로 계산한 피로도 점수(0~100). [replan]과 같은 규칙이다. */
    fun fatigueScore(
        runs: List<RunSession>, today: LocalDate, walks: List<WalkSession> = emptyList(),
        cross: List<ActivitySession> = emptyList(),
    ): Int {
        val sorted = runs.filter { it.date <= today }.sortedBy { it.start }
        if (sorted.isEmpty()) return 0
        val ranToday = sorted.any { it.date == today }
        val last7 = sorted.filter { it.date >= today.minusDays(6) }
        val last28 = sorted.filter { it.date >= today.minusDays(27) }
        val runKm7 = last7.sumOf { it.distanceKm }
        val walkEq7 = walkEq(walks, today, 7)
        val easyLoad = easyPaceOf(last28)
        val crossEq7 = crossEq(cross, walks, today, 7, easyLoad)
        val acute = runKm7 + walkEq7 + crossEq7
        val chronic = (last28.sumOf { it.distanceKm } + walkEq(walks, today, 28) + crossEq(cross, walks, today, 28, easyLoad)) / 4.0
        val kmBase = baseKm(sorted, today)
        val acwr = if (chronic > 0.1) acute / chronic else 1.0
        val weekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val weekKm = sorted.filter { it.date >= weekStart }.sumOf { it.distanceKm }
        val hardRecent = sorted.filter { it.date >= today.minusDays(3) && isHard(it) }
        val longRecent = sorted.filter { it.date >= today.minusDays(2) }.maxOfOrNull { it.distanceKm } ?: 0.0
        var consecutive = 0
        var cursor = if (ranToday) today else today.minusDays(1)
        while (sorted.any { it.date == cursor }) {
            consecutive++
            cursor = cursor.minusDays(1)
        }
        val runs4d = sorted.count { it.date >= today.minusDays(3) }
        var score = 0
        score += when { acwr >= 1.5 -> 40; acwr >= 1.3 -> 28; acwr >= 1.15 -> 12; else -> 0 }
        score += when { weekKm >= kmBase * 1.5 -> 15; weekKm >= kmBase * 1.2 -> 10; weekKm >= kmBase * 0.95 -> 4; else -> 0 }
        score += minOf(hardRecent.size * 18, 36)
        score += when { longRecent >= kmBase * 0.50 -> 25; longRecent >= kmBase * 0.40 -> 20; longRecent >= kmBase * 0.33 -> 10; else -> 0 }
        score += when { consecutive >= 4 -> 20; consecutive == 3 -> 12; consecutive == 2 -> 6; else -> 0 }
        if (runs4d >= 3) score += 8
        return score.coerceIn(0, 100)
    }


    /** 지금 기준 피로도: 러닝 피로도에 건강 신호 가산점을 더한 값. 새 계획을 만들 때 쓰는 점수와 같은 규칙이다 */
    class FatigueNow(val score: Int, val running: Int, val healthAdd: Int, val level: String)

    fun fatigueNow(
        runs: List<RunSession>,
        today: LocalDate,
        walks: List<WalkSession> = emptyList(),
        readiness: Readiness? = null,
        cross: List<ActivitySession> = emptyList(),
    ): FatigueNow {
        val running = fatigueScore(runs, today, walks, cross)
        val add = FatigueMath.healthAdd(readiness?.healthPenalty)
        val score = FatigueMath.combine(running, readiness?.healthPenalty)
        return FatigueNow(score, running, add, FatigueMath.level(score))
    }

    private fun round5(v: Double): Int = (v / 5.0).roundToInt() * 5

    // ───────────── 목표별 훈련 형식 (점진 상승) ─────────────

    /** 템포 한 번의 구성. reps가 1이면 연속, 2 이상이면 구간 사이에 짧게 쉬는 크루즈 템포 */
    class TempoPlan(val totalMin: Int, val reps: Int, val segMin: Int, val restSec: Int)

    /** 템포(T 페이스) 작업량 상한: 주간 거리의 10% (다니엘스 기준) */
    const val TEMPO_VOL_PCT = 0.10
    /**
     * 실력 기준 페이스를 '최고 8km 연속 구간(최근 8주)' 방식으로 정할지. false면 지금 방식(최근 4주 8km 이상 러닝의 가장 빠른 평균)이다.
     * 두 값은 항상 계획 노트에 같이 보여준다. 실제 기록으로 확인한 뒤 true로 바꾼다.
     */
    const val USE_NEW_ANCHOR = false

    /** 인터벌(I 페이스) 작업량 상한: 주간 거리의 8%, 한 번에 최대 10km (다니엘스 기준) */
    const val INTERVAL_VOL_PCT = 0.08
    /** 주간 거리가 적어도 보장하는 최소 1km 반복 수. 주 35~40km에서는 주간 거리의 약 13%라 다니엘스 8%보다 높지만, 기존 계획 수준(5회)을 유지하려고 직접 정한 값 */
    const val INTERVAL_MIN_REPS = 5
    /** 하프의 긴 반복(하프 페이스 부근) 작업량 상한: 주간 거리의 15% (역치 10%와 마라톤 페이스 20% 사이로 잡은 값) */
    const val HALF_REPEAT_PCT = 0.15
    /** 풀의 긴 반복(마라톤 페이스) 작업량 상한: 주간 거리의 20% (일반적인 관행을 참고한 값) */
    const val LONG_REPEAT_MP_PCT = 0.20

    /**
     * 목표 템포 길이[targetMin]를 주간 거리·목표 종목 상한으로 깎고, 연속/크루즈 중 하나로 짠다.
     * 연속 템포는 최대 30분(주간 60km 이상이면 40분), 그보다 길면 15분 안팎으로 나눈다.
     * 20분은 항상 허용한다 (주간 거리가 적어도 고전적인 템포는 가능).
     */
    fun tempoPlanFor(targetMin: Int, base: Double, tempoPaceSec: Int, goalMaxMin: Int): TempoPlan {
        val volCapMin = (base * TEMPO_VOL_PCT * tempoPaceSec / 60.0).toInt()
        val total = (minOf(targetMin, goalMaxMin, volCapMin) / 5 * 5).coerceAtLeast(20)
        val contCap = if (base >= 60.0) 40 else 30
        if (total <= contCap) return TempoPlan(total, 1, total, 0)
        val k = Math.ceil(total / 15.0).toInt()
        val seg = (total / k.toDouble()).roundToInt()
        return TempoPlan(seg * k, k, seg, if (seg >= 12) 120 else 60)
    }

    /** 하프·풀의 인터벌 칸: [n]회 × [km]km 긴 반복 */
    class LongRepeat(val n: Int, val km: Double)

    private val HALF_REPEATS = arrayOf(3 to 1.5, 3 to 2.0, 4 to 2.0, 3 to 3.0, 4 to 3.0, 5 to 3.0)
    private val FULL_REPEATS = arrayOf(3 to 2.0, 3 to 3.0, 4 to 3.0, 3 to 5.0, 4 to 4.0, 3 to 6.0)

    /**
     * 단계[stage]까지의 표 항목 중 작업량 상한[capKm] 안에서 가장 큰 구성을 고른다.
     * 단계가 오르거나 상한이 커져도 구성이 줄어드는 일이 없다.
     * 어떤 항목도 상한을 넘으면 첫 항목을 줄여서 맞춘다 (횟수 3회, 길이는 최소 1km).
     */
    fun longRepeatFor(stage: Int, isFull: Boolean, capKm: Double): LongRepeat {
        val table = if (isFull) FULL_REPEATS else HALF_REPEATS
        var best: Pair<Int, Double>? = null
        for (i in 0..stage.coerceIn(0, 5)) {
            val (n, l) = table[i]
            if (n * l <= capKm && (best == null || n * l >= best.first * best.second)) best = n to l
        }
        if (best != null) return LongRepeat(best.first, best.second)
        var l0 = table[0].second
        while (l0 > 1.0 && 3 * l0 > capKm) l0 -= 0.5
        return LongRepeat(3, l0)
    }

    /** 실행 게이트 결과: 지난 4주 퀄리티 훈련 수행 정도에 따라 다음 계획의 단계를 올리거나 유지하거나 내린다 */
    private enum class ProgGate { UP, HOLD, DOWN }

    fun kmTxt(x: Double): String = if (x % 1.0 == 0.0) x.toInt().toString() else "%.1f".format(x)

    // ───────────── 블록 중간 조정 ─────────────

    /** 자동 조정 결과: 바뀐 계획과 사용자에게 알려줄 변경 내용 (바꾼 게 없으면 changes가 비어 있다) */
    class MicroResult(val plan: List<PlanItem>, val changes: List<String>)

    /** 사용자가 "원래대로"를 눌러 유지하기로 한 훈련 표시. 이 표시가 있으면 다시 자동 조정하지 않는다 */
    private const val ADJ_KEEP = "KEEP"

    fun isAdjusted(p: PlanItem): Boolean = p.adj.isNotEmpty() && p.adj != ADJ_KEEP

    /** 조정된 훈련의 조정 이유 (조정되지 않았으면 빈 문자열) */
    fun adjReason(p: PlanItem): String =
        if (!isAdjusted(p)) "" else runCatching { org.json.JSONObject(p.adj).optString("reason") }.getOrDefault("")

    /** 조정 전 훈련을 기록해 둔다 (되돌리기용) */
    private fun adjSnapshot(p: PlanItem, reason: String): String {
        val o = org.json.JSONObject()
        o.put("reason", reason)
        o.put("type", p.type.name)
        o.put("title", p.title)
        o.put("km", p.km)
        o.put("reps", p.reps)
        o.put("pace", p.targetPace)
        o.put("stage", p.stage)
        o.put("paceAdj", p.paceAdj)
        o.put("tips", org.json.JSONArray(p.tips))
        return o.toString()
    }

    /** "원래대로": 조정 전 훈련을 되살리고, 다시 자동 조정하지 않도록 표시한다 */
    fun revert(p: PlanItem): PlanItem {
        if (!isAdjusted(p)) return p
        val o = runCatching { org.json.JSONObject(p.adj) }.getOrNull() ?: return p.copy(adj = ADJ_KEEP)
        val arr = o.optJSONArray("tips")
        val tips = if (arr != null) (0 until arr.length()).map { arr.getString(it) } else p.tips
        return p.copy(
            type = runCatching { WorkoutType.valueOf(o.optString("type")) }.getOrDefault(p.type),
            title = o.optString("title", p.title),
            km = o.optDouble("km", p.km),
            reps = o.optInt("reps", p.reps),
            targetPace = o.optInt("pace", p.targetPace),
            stage = o.optInt("stage", p.stage),
            paceAdj = o.optInt("paceAdj", p.paceAdj),
            tips = tips,
            adj = ADJ_KEEP,
        )
    }

    /**
     * 블록 중간 점검. 4주 계획 도중에 피로도가 높거나 최근 7일 부하가 평소의 130%를 넘으면 가까운 훈련을 낮춘다.
     * - 오늘·내일의 인터벌·템포 → 이지런 (거리 80%)
     * - 부하 급증이면 이번 주 남은 이지런·롱런 거리 15% 감소
     * 내리는 방향으로만 자동 조정한다. 직접 옮긴 훈련, 지난 날짜, 오늘 이미 끝낸 훈련, 이미 조정했거나 유지하기로 한 훈련은 건드리지 않는다.
     * 조정된 훈련은 adj에 원본을 기록해 두므로 [revert]로 되돌릴 수 있고, 인터벌·템포가 아니게 되어 실행 게이트의 미실시에서 빠진다.
     */
    fun microAdjust(
        plan: List<PlanItem>,
        runs: List<RunSession>,
        today: LocalDate,
        readiness: Readiness? = null,
        walks: List<WalkSession> = emptyList(),
        cross: List<ActivitySession> = emptyList(),
    ): MicroResult {
        val sorted = runs.filter { it.date <= today }.sortedBy { it.start }
        val fat = fatigueNow(sorted, today, walks, readiness, cross)
        val runKm7 = sorted.filter { it.date >= today.minusDays(6) }.sumOf { it.distanceKm }
        val easyLoad = easyPaceOf(sorted.filter { it.date >= today.minusDays(27) })
        val acute = runKm7 + walkEq(walks, today, 7) + crossEq(cross, walks, today, 7, easyLoad)
        val chronic = (sorted.filter { it.date >= today.minusDays(27) }.sumOf { it.distanceKm } + walkEq(walks, today, 28) +
            crossEq(cross, walks, today, 28, easyLoad)) / 4.0
        val acwr = if (chronic > 0.1) acute / chronic else 1.0
        val highFatigue = fat.level == "높음"
        val spike = acwr >= 1.3
        if (!highFatigue && !spike) return MicroResult(plan, emptyList())

        val reason = if (highFatigue) "피로도가 높아서" else "최근 7일 부하가 평소의 130%를 넘어서"
        val easy = easyPaceOf(sorted.filter { it.date >= today.minusDays(27) })
        val easyTxt = "${formatPace((easy - 5).toDouble())}~${formatPace((easy + 10).toDouble())}"
        val doneToday = match(plan.filter { it.date == today }, sorted).keys
        val weekEnd = today.plusDays((7 - today.dayOfWeek.value).toLong())
        val changes = mutableListOf<String>()

        val out = plan.map { p ->
            if (p.date < today || p.rescheduled || p.adj.isNotEmpty() || p.id in doneToday) return@map p
            val isQuality = p.type == WorkoutType.INTERVAL || p.type == WorkoutType.TEMPO
            when {
                isQuality && !p.optional && p.date <= today.plusDays(1) -> {
                    val km = maxOf(3.0, Math.round(p.km * 0.8).toDouble())
                    val q = p.copy(
                        type = WorkoutType.EASY, title = "이지런 ${km.toInt()}km", km = km, reps = 0, targetPace = easy,
                        tips = listOf(
                            "페이스 $easyTxt, 심박 ${UserProfile.EASY_HR} 이하",
                            "$reason 퀄리티 훈련을 이지런으로 낮췄어요",
                            "컨디션이 돌아오면 다음 퀄리티 훈련은 그대로 진행해요",
                        ),
                        adj = adjSnapshot(p, "$reason 오늘·내일 퀄리티 훈련을 이지런으로 낮췄어요"),
                    )
                    changes += "${dayLabel(p.date)} ${p.title} → ${q.title}"
                    q
                }
                spike && !p.optional && (p.type == WorkoutType.EASY || p.type == WorkoutType.LONG) && p.date <= weekEnd -> {
                    val km = maxOf(3.0, Math.round(p.km * 0.85).toDouble())
                    if (km >= p.km - 0.4) {
                        p
                    } else {
                        val q = p.copy(
                            title = Regex("\\d+(?:\\.\\d+)?km").replaceFirst(p.title, "${km.toInt()}km"),
                            km = km,
                            tips = p.tips + "$reason 거리를 줄였어요",
                            adj = adjSnapshot(p, "$reason 이번 주 거리를 15% 줄였어요"),
                        )
                        changes += "${dayLabel(p.date)} ${p.title} → ${q.title}"
                        q
                    }
                }
                else -> p
            }
        }
        return MicroResult(out, changes)
    }

    /** 계획 탭에 보여줄 점검 제안. 계획을 바꾸지는 않는다 */
    fun proposals(plan: List<PlanItem>, runs: List<RunSession>, today: LocalDate): List<String> {
        val out = mutableListOf<String>()
        val recent = plan.filter {
            !it.optional && (it.type == WorkoutType.INTERVAL || it.type == WorkoutType.TEMPO) &&
                it.date < today && it.date >= today.minusDays(14)
        }
        if (recent.size >= 2) {
            val m = match(recent, runs.filter { it.date <= today })
            if (recent.none { m.containsKey(it.id) }) {
                out += "최근 2주 동안 계획한 퀄리티 훈련 ${recent.size}개를 모두 건너뛰었어요. '새 계획'을 누르면 소화한 정도에 맞춰 단계와 거리를 조정해요."
            }
        }
        return out
    }

    // ───────────── AI 코치 계획 변경 ─────────────

    /**
     * AI 코치가 제안한 오늘 훈련 변경을 계획에 반영한다.
     * 바뀐 계획과 사용자에게 보여줄 결과 문구를 돌려주고, 바뀐 게 없으면 받은 계획 객체를 그대로 돌려준다.
     * - 쉬기(type == null): 오늘 훈련을 뺀다
     * - 오늘 훈련이 있으면: 새 훈련으로 바꾸고 원본을 기록해 둔다 (계획 탭의 "원래대로"로 되돌릴 수 있다)
     * - 오늘 훈련이 없으면: 오늘 훈련으로 추가한다
     * 오늘 이미 러닝을 했으면 바꾸지 않는다.
     */
    fun applyChatChange(
        plan: List<PlanItem>,
        change: AiCoach.PlanChange,
        today: LocalDate,
        runs: List<RunSession>,
    ): Pair<List<PlanItem>, String> {
        if (runs.any { it.date == today }) return plan to "오늘은 이미 러닝을 하셔서 오늘 계획은 바꾸지 않았어요."
        val todayItem = plan.firstOrNull { it.date == today }
        val type = change.type
        if (type == null) {
            if (todayItem == null) return plan to "오늘은 원래 계획된 훈련이 없어서 그대로 쉬시면 돼요."
            return plan.filter { it.id != todayItem.id } to
                "✅ 오늘 훈련(${todayItem.title})을 뺐어요. 푹 쉬세요. 계획 탭의 '새 계획'을 누르면 다시 채워져요."
        }
        val week = todayItem?.week
            ?: plan.minByOrNull { Math.abs(it.date.toEpochDay() - today.toEpochDay()) }?.week
            ?: "1"
        val built = buildChatWorkout(type, change.km, today, week, plan, runs)
        val kmStr = "%.1f".format(built.km)
        if (todayItem == null) {
            return (plan + built).sortedBy { it.date } to
                "✅ 오늘 훈련으로 ${built.title} (${kmStr}km)를 추가했어요. 취소하려면 '오늘은 쉬고 싶어'라고 말씀해 주세요."
        }
        val replaced = built.copy(
            id = todayItem.id, week = todayItem.week, rescheduled = todayItem.rescheduled,
            adj = adjSnapshot(todayItem, "AI 코치 제안: ${change.reason}"),
        )
        return plan.map { if (it.id == todayItem.id) replaced else it } to
            "✅ 오늘 훈련을 바꿨어요: ${todayItem.title} → ${replaced.title} (${kmStr}km). 계획 탭에서 '원래대로'로 되돌릴 수 있어요."
    }

    /** 채팅에서 정한 훈련을 계획 항목으로 만든다. 이지런·롱런만 AI가 준 거리를 쓰고, 나머지는 앱이 정한 구성으로 만든다 */
    private fun buildChatWorkout(
        type: WorkoutType, km: Double, date: LocalDate, week: String,
        plan: List<PlanItem>, runs: List<RunSession>,
    ): PlanItem {
        val last28 = runs.filter { it.date >= date.minusDays(27) }
        val easy = easyPaceOf(last28)
        val easyTxt = "${formatPace((easy - 5).toDouble())}~${formatPace((easy + 10).toDouble())}"
        val goal = UserProfile.GOAL_10K_PACE
        val ref = last28
            .filter { (if (it.mainKm > 0.5) it.mainKm else it.distanceKm) >= 8.0 }
            .map { it.paceSecPerKm }.filter { it > 0 }.minOrNull() ?: (goal + 17).toDouble()
        val warmKm = 720.0 / easy
        val coolKm = 600.0 / (easy + 15.0)
        val past = plan.filter { it.date < date }
        fun adjOf(t: WorkoutType): Int = past.filter { it.type == t }.maxByOrNull { it.date }?.paceAdj ?: 0
        fun round1(x: Double): Double = Math.round(x * 10) / 10.0
        val note = "AI 코치와 상의해서 정한 훈련이에요"
        return when (type) {
            WorkoutType.EASY -> {
                val k = Math.round(km.coerceIn(3.0, 20.0)).toDouble()
                PlanItem(
                    date, week, type, "이지런 ${k.toInt()}km", k, false,
                    listOf("페이스 $easyTxt, 심박 ${UserProfile.EASY_HR} 이하", "대화가 가능한 강도로 편하게", note),
                    targetPace = easy,
                )
            }
            WorkoutType.LONG -> {
                val k = Math.round(km.coerceIn(6.0, 35.0)).toDouble()
                PlanItem(
                    date, week, type, "롱런 ${k.toInt()}km", k, false,
                    listOf("$easyTxt 로 끝까지 일정하게", "6~8km 지점에서 물 마시기", note),
                    targetPace = easy,
                )
            }
            WorkoutType.TEMPO -> {
                val pace = maxOf(round5(maxOf(ref + 12.0, (goal + 10).toDouble())) + adjOf(WorkoutType.TEMPO), goal + 10)
                val k = round1(warmKm + 1200.0 / pace + coolKm)
                PlanItem(
                    date, week, type, "템포 20분", k, false,
                    listOf(
                        "웜업 12분 @${formatPace(easy.toDouble())}",
                        "본운동 20분을 ${formatPace(pace.toDouble())}로 일정하게 (심박 148~158)",
                        "쿨다운 10분 + 스트레칭", note,
                    ),
                    targetPace = pace, paceAdj = adjOf(WorkoutType.TEMPO),
                )
            }
            WorkoutType.INTERVAL -> {
                val reps = INTERVAL_MIN_REPS
                val pace = maxOf(round5(maxOf(ref - 12.0, (goal - 5).toDouble())) + adjOf(WorkoutType.INTERVAL), goal - 5)
                val k = round1(warmKm + reps + (reps - 1) * 0.3 + coolKm)
                PlanItem(
                    date, week, type, "인터벌 1km×$reps", k, false,
                    listOf(
                        "웜업 12분 @${formatPace(easy.toDouble())} + 스트라이드",
                        "1km × ${reps}회 @${formatPace(pace.toDouble())}, 사이 2분 조깅",
                        "쿨다운 10분 후 스트레칭", note,
                    ),
                    targetPace = pace, reps = reps, paceAdj = adjOf(WorkoutType.INTERVAL),
                )
            }
            WorkoutType.TEST -> {
                PlanItem(
                    date, week, type, "10K 기록 측정", 10.0, false,
                    listOf(
                        "첫 1km는 ${formatPace(ref + 8.0)} 안팎으로 절제",
                        "2~8km 일정하게, 마지막 2km에서 여력만큼",
                        "전날 휴식, 전 2주간 퀄리티를 지킨 컨디션에서 도전", note,
                    ),
                    targetPace = ref.toInt(),
                )
            }
        }
    }

    /** 퀄리티 훈련에서 실제로 달린 반복/본 구간들 (평가와 같은 기준) */
    private fun qualitySegs(p: PlanItem, r: RunSession): List<RunSegment> {
        val main = r.segments.filter { it.role == SegRole.MAIN && it.kind == SegKind.RUN }
        return if (p.type == WorkoutType.INTERVAL) {
            val repKm = Regex("(\\d+(?:\\.\\d+)?)km×").find(p.title)?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0
            val expSec = (repKm * targetPace(p)).toInt()
            val lo = if (repKm <= 1.0) 120 else (expSec * 0.5).toInt()
            val hi = if (repKm <= 1.0) 420 else (expSec * 1.6).toInt()
            main.filter { it.durSec in lo..hi }
        } else {
            main.filter { it.durSec >= 300 }
        }
    }

    /**
     * 한국 월별 러닝 적합도 계수. 여름(7~8월)은 고온다습으로 감소, 봄·가을은 최적이라 소폭 증가.
     * seasonAware=false이면 1.0을 돌려준다.
     * 대회 테이퍼 단계에서는 계절 조정을 절반만 반영한다(대회는 날씨와 무관하게 뛰어야 하므로).
     */
    /**
     * 체감온도 기반 월별 러닝 적합도 계수.
     * 열지수(여름: 기온 + 습도)와 풍속 체감온도(겨울)를 적용해 운동 중 실제 부담을 반영했어요.
     * 평균기온 대비 주요 차이: 7~8월 더 엄격(0.68~0.65), 1~2월 더 엄격(0.88~0.90), 9월 완화(0.80).
     */
    fun seasonCoeff(month: Int, racePhase: String = "GENERAL"): Double {
        val base = when (month) {
            1  -> 0.88   // 체감 −8°C — 한파, 풍속 체감온도 반영
            2  -> 0.90   // 체감 −6°C — 한파 끝물, 블랙아이스
            3  -> 1.00   // 체감 8°C  — 봄 시작
            4  -> 1.05   // 체감 15°C — 최적 시즌
            5  -> 1.00   // 체감 20°C — 쾌적, 말에 더위 시작
            6  -> 0.88   // 체감 25°C — 고온다습, 열지수 반영
            7  -> 0.68   // 체감 43°C — 폭염·장마, 열지수 최고조
            8  -> 0.65   // 체감 46°C — 연중 최악, 운동 위험 수준
            9  -> 0.80   // 체감 34°C — 초가을도 습도 높아 더위 지속
            10 -> 1.05   // 체감 18°C — 가을 최적
            11 -> 1.00   // 체감 10°C — 서늘·쾌적
            12 -> 0.93   // 체감 −4°C — 초겨울, 기온 급강하
            else -> 1.00
        }
        // 테이퍼·최종·대회 주간은 계절 조정을 절반만 반영 (대회 날짜는 선택 불가)
        return if (racePhase == "TAPER" || racePhase == "FINAL" || racePhase == "RACE")
            1.0 + (base - 1.0) * 0.5 else base
    }

    /** 계절 단계 이름 */
    fun seasonLabel(month: Int): String = when (month) {
        1, 2 -> "겨울"; 3, 4, 5 -> "봄"; 6, 7, 8 -> "여름"; 9, 10, 11 -> "가을"; else -> "초겨울"
    }

    /**
     * 계절 경고 등급 — null이면 경고 없음, "주의"/"위험"으로 구분.
     * 계획 카드에 배너로 표시하고, 훈련 아이템 팁에도 추가돼요.
     */
    fun seasonWarnLevel(month: Int): String? = when (month) {
        6    -> "주의"
        7    -> "위험"
        8    -> "위험"
        9    -> "주의"
        1, 2 -> "주의"
        else -> null
    }

    /**
     * 계절별 상세 조언.
     * 경고 배너(계획 카드)에는 첫 줄만, 이유 보기에는 전체가 들어가요.
     */
    fun seasonTip(month: Int): String {
        fun tips(vararg lines: String) = lines.joinToString("\n")
        return when (month) {
            7 -> tips(
                "🌡️ 한여름 폭염·장마 시즌이에요.",
                "• 가능하면 이른 새벽(5~7시)에 달리세요. 오전 10시~오후 6시 사이 야외 러닝은 피하는 게 좋아요.",
                "• 출발 전 300ml, 달리는 중 20분마다 150~200ml씩 수분을 보충하세요.",
                "• 심박수가 평소보다 10bpm 이상 높으면 페이스를 줄이거나 멈추세요.",
                "• 어지럼증·메스꺼움·두통이 오면 즉시 그늘로 이동하고 수분을 섭취하세요(열사병 초기 증상).",
                "• 페이스는 평소보다 20~30초/km 늦추는 게 정상이에요. 무리하지 마세요.",
            )
            8 -> tips(
                "🌡️ 연중 가장 덥고 습한 시기예요. 열지수 기준 체감온도가 46°C에 달해요.",
                "• 가능하면 이른 새벽(5시 이전)에 달리거나 실내 트레드밀로 대체하세요.",
                "• 거리를 평소의 65% 이하로 줄이는 게 좋아요. 무리한 훈련은 오히려 역효과예요.",
                "• 출발 전 충분히 수분을 보충하고, 20분마다 150~200ml씩 보충하세요.",
                "• 땀을 많이 흘릴 경우 이온음료나 소금 섭취도 도움이 돼요(전해질 보충).",
                "• 열사병 증상(어지럼·구역·피부 붉어짐)이 오면 즉시 중단하고 119에 연락하세요.",
            )
            6 -> tips(
                "🌡️ 고온다습한 시기예요. 장마 전 습도가 급격히 올라요.",
                "• 페이스를 평소보다 10~15초/km 늦추고, 심박수 위주로 강도를 조절하세요.",
                "• 20분마다 물을 마시고, 출발 전 300ml 이상 수분을 미리 채워 두세요.",
                "• 장마 중 비를 맞으며 달릴 땐 미끄러운 길과 낙뢰 주의가 필요해요.",
            )
            9 -> tips(
                "🍂 아직 습도와 열기가 남아 있어요. 체감온도는 34°C 수준이에요.",
                "• 중순까지는 여름 수분 보충 습관을 유지하세요.",
                "• 말부터 기온이 빠르게 내려가니, 가을 시즌을 위한 거리 증량 계획을 세우기 좋아요.",
            )
            1 -> tips(
                "🧊 한파 시즌이에요. 풍속 체감온도가 −8°C 아래예요.",
                "• 준비운동을 10분 이상 충분히 하세요. 차가운 공기에 근육이 굳기 쉬워요.",
                "• 장갑·귀마개·방풍 레이어를 착용하고, 목도리로 기도를 보호하세요.",
                "• 체감온도 −15°C 이하이거나 한파 경보가 발령된 날은 실내 트레드밀로 대체하세요.",
                "• 블랙아이스(투명 얼음)가 그늘진 길·다리에 있을 수 있어요. 발을 넓게 딛고 달리세요.",
            )
            2 -> tips(
                "🧊 한파가 풀리는 시기지만 블랙아이스가 가장 많은 달이에요.",
                "• 그늘진 길·지하보도 출입구·다리 위는 살얼음이 있을 수 있어요.",
                "• 준비운동을 충분히 하고 방풍·방한 레이어를 유지하세요.",
                "• 말에 기온이 오르면서 봄 훈련 준비를 시작하기 좋아요.",
            )
            4 -> tips(
                "🌸 러닝 최적 시즌이에요. 기온·습도 모두 이상적이에요.",
                "• 목표 페이스 훈련과 롱런에 집중하기 가장 좋은 시기예요.",
                "• 미세먼지 농도를 확인하고 나쁨일 때는 마스크를 착용하거나 실내로 대체하세요.",
            )
            10 -> tips(
                "🍁 가을 최적 시즌이에요. 한 해 중 가장 달리기 좋은 조건이에요.",
                "• 목표 대회가 있다면 이 시기에 최고 퍼포먼스를 낼 수 있어요.",
                "• 말부터 기온이 빠르게 내려가니 겨울 훈련 준비도 함께 시작하세요.",
            )
            else -> ""
        }
    }

        /** 대회까지 남은 주 수로 현재 훈련 단계 이름 반환 */
    fun racePhaseLabel(weeksLeft: Int, goalKey: String): String = when {
        weeksLeft <= 0  -> "대회 주간 🏁"
        weeksLeft <= 2  -> "최종 조정"
        weeksLeft <= 5  -> "테이퍼 (훈련량 감소)"
        weeksLeft <= 11 -> "피크 (최대 강도)"
        weeksLeft <= 19 -> "빌드업 (거리·강도 증가)"
        else            -> "베이스빌딩 (기초 쌓기)"
    }

    private fun dayLabel(d: LocalDate): String = "${d.monthValue}/${d.dayOfMonth}(${korDow(d.dayOfWeek)})"

    private fun pctText(v: Double): String = "${Math.round(v * 100)}%"

    /**
     * 최근 러닝(거리·강도·연속일)으로 피로도를 계산하고, 그에 맞춰 앞으로 4주 계획을 새로 만든다.
     * 지나간 계획은 그대로 두고, 오늘(또는 내일)부터의 계획만 바꾼다. 왜 바꿨는지는 [PlanUpdate.note]에 담긴다.
     */
    fun replan(runs: List<RunSession>, oldPlan: List<PlanItem>, today: LocalDate, readiness: Readiness? = null, walks: List<WalkSession> = emptyList(), anchor: LocalDate? = null, cross: List<ActivitySession> = emptyList()): PlanUpdate {
        val sorted = runs.filter { it.date <= today }.sortedBy { it.start }
        val ranToday = sorted.any { it.date == today }
        val last7 = sorted.filter { it.date >= today.minusDays(6) }
        val last28 = sorted.filter { it.date >= today.minusDays(27) }
        val runKm7 = last7.sumOf { it.distanceKm }
        val walkEq7 = walkEq(walks, today, 7)
        val easyLoad = easyPaceOf(last28)
        val crossEq7 = crossEq(cross, walks, today, 7, easyLoad)
        val acute = runKm7 + walkEq7 + crossEq7
        val chronic = (last28.sumOf { it.distanceKm } + walkEq(walks, today, 28) + crossEq(cross, walks, today, 28, easyLoad)) / 4.0
        val kmBase = baseKm(sorted, today)
        val acwr = if (chronic > 0.1) acute / chronic else 1.0
        val weekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val weekRuns = sorted.filter { it.date >= weekStart }
        val weekKm = weekRuns.sumOf { it.distanceKm }
        val lastRun = sorted.lastOrNull()
        val hardRecent = sorted.filter { it.date >= today.minusDays(3) && isHard(it) }
        val longRecent = sorted.filter { it.date >= today.minusDays(2) }.maxOfOrNull { it.distanceKm } ?: 0.0
        var consecutive = 0
        var cursor = if (ranToday) today else today.minusDays(1)
        while (sorted.any { it.date == cursor }) {
            consecutive++
            cursor = cursor.minusDays(1)
        }

        // ── 피로도 점수: 러닝 피로도 + 건강 신호 가산점 (계획 탭의 '지금 피로도'와 같은 규칙) ──
        val runningScore = fatigueScore(runs, today, walks, cross)
        val healthAdd = FatigueMath.healthAdd(readiness?.healthPenalty)
        val score = (runningScore + healthAdd).coerceIn(0, 100)
        val level = FatigueMath.level(score)
        val levelRest = when (level) { "높음" -> 2; "보통" -> 1; else -> 0 }
        // 점수가 낮아도 장거리 직후·주 4회 초과 같은 규칙에 걸리면 쉰다 (AI 평가와 같은 규칙을 쓴다)
        val adv = recoveryAdvice(sorted, today)
        val restDays = maxOf(levelRest, adv.restDays)
        val eff = if (level == "낮음" && adv.restDays > 0) "보통" else level

        // ── 다시 달리기 시작할 날짜 ──
        val lastDate = lastRun?.date ?: today.minusDays(7)
        val earliest = if (ranToday) today.plusDays(1) else today
        val firstRun = maxOf(lastDate.plusDays((restDays + 1).toLong()), earliest)
        val firstMonday = firstRun.minusDays((firstRun.dayOfWeek.value - 1).toLong())

        // ── 현재 체력 기준 페이스 (최근 4주 8km 이상 러닝 중 가장 빠른 페이스 = 10K 근처 기록) ──
        val refPace = last28
            .filter { (if (it.mainKm > 0.5) it.mainKm else it.distanceKm) >= 8.0 }
            .map { it.paceSecPerKm }.filter { it > 0 }.minOrNull()
        // 새 방식: 최근 8주 8km 이상 러닝에서 '가장 빠른 8km 연속 구간' 페이스. 워밍업·쿨다운·걷기를 뺀 지속 능력이다.
        fun anchorAt(day: LocalDate): Double? = sorted
            .filter { it.date <= day && it.date > day.minusDays(56) }
            .filter { (if (it.mainKm > 0.5) it.mainKm else it.distanceKm) >= 8.0 }
            .mapNotNull { r ->
                val sp = r.splits
                if (sp.size >= 8) (0..sp.size - 8).minOf { a -> sp.subList(a, a + 8).average() }
                else r.paceSecPerKm.takeIf { it > 0 }
            }
            .minOrNull()
        val anchorPrev = anchorAt(today.minusDays(28))
        val anchorRaw = anchorAt(today)
        // 우연히 빨랐던 기록을 막으려고 4주 전 값보다 6초/km 넘게 빨라지지 않게 제한한다 (느려지는 것은 제한하지 않는다)
        val anchorNow: Double? =
            if (anchorRaw != null && anchorPrev != null) maxOf(anchorRaw, anchorPrev - 6.0) else anchorRaw
        // 어느 방식으로 계획을 만들지는 USE_NEW_ANCHOR로 정한다. 확인 전에는 지금 방식을 쓰고 두 값을 계획 노트에 같이 보여준다.
        val useNew = USE_NEW_ANCHOR && anchorNow != null
        val refBase: Double? = if (useNew) anchorNow else refPace
        val ref = refBase ?: (UserProfile.GOAL_10K_PACE + 17).toDouble()
        val goal = UserProfile.GOAL_10K_PACE
        val tempoPace = round5(maxOf(ref + 12.0, (goal + 10).toDouble()))
        val intervalPace = round5(maxOf(ref - 12.0, (goal - 5).toDouble()))
        val easy = easyPaceOf(last28)
        val easyTxt = "${formatPace((easy - 5).toDouble())}~${formatPace((easy + 10).toDouble())}"

        // ── 대회까지 남은 주 수로 훈련 단계 결정 ──
        val rd = UserProfile.data.raceDate
        val weeksToRace: Int? = rd?.let { java.time.temporal.ChronoUnit.WEEKS.between(today, it).toInt().coerceAtLeast(0) }
        val isLongGoal = UserProfile.data.goalDistanceKm >= 21.0
        val racePhase: String = when {
            weeksToRace == null -> "GENERAL"
            weeksToRace <= 0    -> "RACE"
            weeksToRace <= 2    -> "FINAL"
            weeksToRace <= 5    -> "TAPER"
            weeksToRace <= 11   -> "PEAK"
            weeksToRace <= 19   -> "BUILD"
            else                -> "BASE"
        }
        val phaseMultBase = when (racePhase) {
            "BASE"  -> doubleArrayOf(0.90, 1.00, 1.05, 0.90)
            "BUILD" -> doubleArrayOf(0.95, 1.05, 1.10, 0.90)
            "PEAK"  -> doubleArrayOf(1.00, 1.08, 1.12, 0.90)
            "TAPER" -> doubleArrayOf(0.75, 0.60, 0.50, 0.40)
            "FINAL" -> doubleArrayOf(0.40, 0.30, 0.25, 0.20)
            "RACE"  -> doubleArrayOf(0.20, 0.10, 0.10, 0.10)
            else    -> doubleArrayOf(0.85, 1.00, 1.05, 0.85)
        }
        val fatigueAdj = when (eff) { "높음" -> 0.80; "보통" -> 0.92; else -> 1.00 }

        // ── 주차별 목표 거리/롱런 ──
        // 강도 레벨 선언은 아래 longMaxKm 블록에 있음
        val lvl = UserProfile.data.intensityLevel
        val lvlAdj = when (lvl) { -1 -> 0.80; 1 -> 1.10; else -> 1.00 }
        val mult = DoubleArray(4) { phaseMultBase[it] * fatigueAdj * lvlAdj }
        // ── 점진 상승 판정 ──
        // 꾸준히 달렸고, 피로가 높지 않고, 부하가 급증하지 않았고, 페이스가 떨어지지 않았을 때만 계획을 올린다.
        val prevRefOld = sorted
            .filter { it.date < today.minusDays(27) && it.date >= today.minusDays(55) }
            .filter { (if (it.mainKm > 0.5) it.mainKm else it.distanceKm) >= 8.0 }
            .map { it.paceSecPerKm }.filter { it > 0 }.minOrNull()
        // 추세(개선/하락) 판정은 실력 기준을 정하는 방식과 같은 값으로 한다
        val trendNow: Double? = if (useNew) anchorNow else refPace
        val prevRef: Double? = if (useNew) anchorPrev else prevRefOld
        val declining = trendNow != null && prevRef != null && trendNow >= prevRef + 8.0
        val improved = trendNow != null && prevRef != null && trendNow <= prevRef - 4.0
        val minRuns = maxOf(8, (UserProfile.RUN_DAYS * 4 * 0.65).roundToInt())
        val peakPhase = racePhase == "TAPER" || racePhase == "FINAL" || racePhase == "RACE"
        val canProgress = !peakPhase && last28.size >= minRuns && eff != "높음" && acwr <= 1.35 && !declining

        // ── 실행 게이트: 지난 4주에 계획한 퀄리티 훈련(인터벌·템포)을 실제로 해냈는지 ──
        // 수행률을 더 중시한다. 점수는 워치 랩·구간 감지에 영향을 받아서 기준을 느슨하게 둔다.
        val qWindow = oldPlan.filter {
            !it.optional && (it.type == WorkoutType.INTERVAL || it.type == WorkoutType.TEMPO) &&
                it.date < today && it.date >= today.minusDays(28)
        }
        val qMatches = match(qWindow, sorted)
        val qDone = qWindow.count { qMatches.containsKey(it.id) }
        val qScores = qWindow.mapNotNull { p -> qMatches[p.id]?.let { r -> evaluate(p, r).score } }
        val qAvgScore = if (qScores.isNotEmpty()) qScores.average() else 0.0
        val qRate = if (qWindow.isNotEmpty()) qDone.toDouble() / qWindow.size else 1.0
        val gate = when {
            qWindow.size < 2 -> ProgGate.UP   // 기록이 적으면 조건 없이 진행 (예전과 같음)
            qRate < 0.50 || (qScores.isNotEmpty() && qAvgScore < 55.0) -> ProgGate.DOWN
            qRate >= 0.70 && qAvgScore >= 65.0 -> ProgGate.UP
            else -> ProgGate.HOLD
        }

        // 주간 기준 거리: 4주 평균은 회복 주가 섞여 계획이 제자리가 되므로,
        // 가장 많이 달린 3주의 평균에 소폭(관절 주의 3%, 기본 6%)을 더한다. 평균의 115%를 넘지 않는다.
        val runChronic = last28.sumOf { it.distanceKm } / 4.0
        val weekKms = (0 until 4).map { i ->
            val end = today.minusDays(7L * i)
            val start = end.minusDays(6)
            sorted.filter { it.date >= start && it.date <= end }.sumOf { it.distanceKm }
        }
        val loadedKm = weekKms.sortedDescending().take(3).average()
        val growPct = if (UserProfile.data.jointCare) 1.03 else 1.06
        // 상승 조건 충족 시 +6%(관절 주의 +3%), 유지면 가산 없이 가장 많이 달린 3주 수준, 하향이면 4주 평균
        val growBase = if (canProgress && last28.size >= 4 && runChronic >= 5.0) {
            val loaded = maxOf(kmBase, loadedKm)
            when (gate) {
                ProgGate.UP -> (loaded * growPct).coerceAtMost(kmBase * 1.15)
                ProgGate.HOLD -> loaded.coerceAtMost(kmBase * 1.15)
                ProgGate.DOWN -> kmBase
            }
        } else kmBase
        val base = growBase.coerceAtLeast(5.0)

        // 목표 종목별 훈련 상한: 5K·10K는 1km 반복 최대 8회(8km), 풀은 6회. 하프·풀의 주력은 아래 긴 반복이다
        val goalDistKm = UserProfile.data.goalDistanceKm
        val maxReps = if (goalDistKm >= 42.0) 6 else 8
        val maxTempoMin = when { goalDistKm >= 21.0 -> 60; goalDistKm >= 10.0 -> 45; else -> 30 }
        // 단계(0~5)마다 조금씩 올려 5단계에 상한에 도달한다
        fun repsAddFor(stage: Int): Int = ((maxReps - 5) * stage / 5.0).roundToInt()
        fun tempoMinFor(stage: Int): Int = 20 + (((maxTempoMin - 20) * stage / 5.0) / 5.0).roundToInt() * 5
        // 연속 템포가 길수록 느리게 (20분 기준 분당 +0.4초: 40분 +8초, 60분 +16초). 긴 템포를 같은 속도로 달리면 역치를 넘어선다
        fun tempoPaceFor(mins: Int): Int = round5(maxOf(ref + 12.0 + 0.4 * (mins - 20), (goal + 10).toDouble()))

        // 직전 계획이 기록해 둔 단계를 확인해서, 한 번에 두 단계 이상 뛰지 않게 한다
        // (단계가 기록되지 않은 예전 계획은 템포 길이로 추정한다)
        val pastQ = oldPlan.filter { (it.type == WorkoutType.TEMPO || it.type == WorkoutType.INTERVAL) && it.date < today }
        // 가장 최근 퀄리티 훈련의 단계를 읽는다 (예전 높은 단계가 하향 뒤에도 남지 않도록 최댓값이 아니라 최근 값)
        val stageFromField = pastQ.maxByOrNull { it.date }?.stage ?: 0
        val lastTempoMin = pastQ.filter { it.type == WorkoutType.TEMPO }
            .maxByOrNull { it.date }
            ?.let { t ->
                val cr = Regex("(\\d+)×(\\d+)\\s*분").find(t.title)
                if (cr != null) cr.groupValues[1].toInt() * cr.groupValues[2].toInt()
                else Regex("(\\d+)\\s*분").find(t.title)?.groupValues?.get(1)?.toIntOrNull()
            }
        val stageFromTitle = if (lastTempoMin != null) (0..5).minByOrNull { abs(tempoMinFor(it) - lastTempoMin) } ?: 0 else 0
        val prevStage = maxOf(stageFromField, stageFromTitle)
        // 하프·풀은 인터벌 칸을 1km 반복 대신 긴 반복(1.5~6km)으로 바꾼다
        val isFull = goalDistKm >= 42.0
        val isHalfFull = goalDistKm >= 21.0
        val goalRacePace: Double =
            if (UserProfile.data.goalTimeSec > 0 && goalDistKm > 0.0) UserProfile.data.goalTimeSec.toDouble() / goalDistKm else 0.0
        // 긴 반복 페이스: 목표 레이스 페이스. 단 지금 역치 페이스보다 하프는 5초, 풀은 10초 이상 느리게 (목표가 지금 실력보다 빠르면 무리하지 않는다)
        val repPaceHF = round5(maxOf(goalRacePace, (tempoPace + (if (isFull) 10 else 5)).toDouble()))
        // 긴 반복 작업량(km) 상한: 하프는 주간 거리의 15%, 풀은 20% (둘 다 최소 6km는 보장)
        val repeatCapKm = maxOf(base * (if (isFull) LONG_REPEAT_MP_PCT else HALF_REPEAT_PCT), 6.0)
        val tempoVolCapMin = (base * TEMPO_VOL_PCT * tempoPace / 60.0).toInt()
        // 1km 반복 상한: 주간 거리의 8%(1km 반복이라 km = 횟수), 적게 달려도 최소 4회는 보장, 종목 상한을 넘지 않는다
        val intervalRepsCap = maxOf(INTERVAL_MIN_REPS, (base * INTERVAL_VOL_PCT).roundToInt()).coerceAtMost(maxReps)

        // ── 페이스 보정 ──
        // 최근 소화한 같은 종류 퀄리티 훈련의 실제 페이스와 구간 심박으로 목표 페이스를 한 블록에 최대 ±6초씩 보정한다.
        // 보정량은 계획 항목(paceAdj)에 기록하고, 같은 보정량으로 계획된 세션의 결과만 모아서 다음 보정을 정한다.
        // (이미 반영된 결과를 다시 쓰지 않아서 계획을 몇 번 다시 만들어도 같은 값이 나온다)
        fun paceStepFor(type: WorkoutType, oldAdj: Int, minSamples: Int, hrLow: Double, hrHigh: Double): Int {
            val errs = mutableListOf<Double>()
            val hrs = mutableListOf<Double>()
            for (p in qWindow) {
                if (p.type != type || p.paceAdj != oldAdj) continue
                val r = qMatches[p.id] ?: continue
                val segs = qualitySegs(p, r)
                if (segs.size < (if (type == WorkoutType.INTERVAL) 2 else 1)) continue
                val dist = segs.sumOf { it.distanceKm }
                if (dist <= 0.0) continue
                errs += segs.sumOf { it.durSec }.toDouble() / dist - targetPace(p)
                val h = segs.mapNotNull { it.avgHr }
                if (h.isNotEmpty()) hrs += h.average() / UserProfile.MAX_HR
            }
            if (errs.size < minSamples || hrs.isEmpty()) return 0
            val e = errs.sorted()[errs.size / 2]
            val hr = hrs.sorted()[hrs.size / 2]
            return when {
                e <= -8.0 && hr <= hrHigh -> maxOf(-6, (e / 2).roundToInt())   // 목표보다 빨랐고 무리하지 않았다 → 빠르게
                e >= 8.0 && hr >= hrLow -> minOf(6, (e / 2).roundToInt())      // 목표보다 느렸고 노력은 충분했다 → 느리게
                else -> 0
            }
        }
        val ivAdjOld = pastQ.filter { it.type == WorkoutType.INTERVAL }.maxByOrNull { it.date }?.paceAdj ?: 0
        val tpAdjOld = pastQ.filter { it.type == WorkoutType.TEMPO }.maxByOrNull { it.date }?.paceAdj ?: 0
        val ivAdj = (ivAdjOld + paceStepFor(WorkoutType.INTERVAL, ivAdjOld, 2, 0.88, 0.95)).coerceIn(-18, 18)
        val tpAdj = (tpAdjOld + paceStepFor(WorkoutType.TEMPO, tpAdjOld, 1, 0.84, 0.92)).coerceIn(-18, 18)
        val intervalPaceA = maxOf(intervalPace + ivAdj, goal - 5)
        // 하프·풀 목표에서는 인터벌 칸이 긴 반복(하프/마라톤 페이스)이라, 계획 노트에도 그 페이스를 보여준다
        val ivLabel = if (isHalfFull) "긴 반복 ${formatPace((repPaceHF + ivAdj).toDouble())}" else "인터벌 ${formatPace(intervalPaceA.toDouble())}"
        // 10K 기록측정: 진행 가능하면 현재 최고 기록보다 3초 빠르게 도전 (목표 페이스보다 빠르게는 잡지 않는다)
        val testPace = if (canProgress) maxOf(ref - 3.0, goal.toDouble()) else ref
        // 롱런 절대 상한: 목표 종목·단계·현재 실력을 모두 고려
        // "지금 주간 거리 대비 최대 38%"와 "절대 상한" 중 작은 값
        val goalKm = UserProfile.data.goalDistanceKm
        // 롱런 절대 상한: 목표 종목 × 대회 단계 × 현재 주간 거리 모두 고려
        // 비율은 "주간 거리 대비 롱런이 차지하는 비중"이고, 절대 상한은 대회 거리 기반이에요.
        // 하프 빌드업 0.45, 하프 피크 0.52, 풀 빌드업 0.48, 풀 피크 0.58
        // 단, 롱런이 직전 최장 기록 대비 너무 갑자기 오르지 않도록 1회 증가폭을 2km 이내로 제한
        val recentLongRun = sorted.filter { it.date >= today.minusDays(42) }
            .maxOfOrNull { it.distanceKm } ?: 0.0
        // stepLimit: 최근 최장+2.5km 또는 base의 38% 중 큰 값 → 첫 계획 수립 시 너무 보수적이지 않게
        val stepLimit = maxOf((recentLongRun + 2.5).roundToInt(), (base * 0.38).roundToInt())
        val jointAdj = if (UserProfile.data.jointCare) 0.90 else 1.00  // 관절 주의: 10% 보수적
        val absLongMax: Int = when {
            racePhase == "TAPER" || racePhase == "FINAL" || racePhase == "RACE" ->
                minOf(14, (base * 0.26).roundToInt())
            // ── 풀코스 ──
            racePhase == "PEAK" && goalKm >= 42.0 ->
                // 피크: base의 58%, 절대 상한 32km, 주간 거리 1.4배 초과 금지
                minOf(32, (base * 0.58 * jointAdj).roundToInt(), (base * 1.4).roundToInt(), stepLimit)
            racePhase == "BUILD" && goalKm >= 42.0 ->
                minOf(26, (base * 0.48 * jointAdj).roundToInt(), stepLimit)
            goalKm >= 42.0 ->  // BASE·GENERAL
                minOf(22, (base * 0.40 * jointAdj).roundToInt(), stepLimit)
            // ── 하프 ──
            racePhase == "PEAK" && goalKm >= 21.0 ->
                // 피크: base의 52%, 절대 상한 21km
                minOf(21, (base * 0.52 * jointAdj).roundToInt(), stepLimit)
            racePhase == "BUILD" && goalKm >= 21.0 ->
                minOf(18, (base * 0.45 * jointAdj).roundToInt(), stepLimit)
            goalKm >= 21.0 ->  // BASE·GENERAL
                minOf(16, (base * 0.40 * jointAdj).roundToInt(), stepLimit)
            // ── 10K 이하 ──
            goalKm >= 10.0 ->
                minOf(14, (base * 0.34).roundToInt(), stepLimit)
            else ->
                minOf(10, (base * 0.32).roundToInt(), stepLimit)
        }.coerceAtLeast(3)
        // 강도 레벨로 ±20% 조정 (최소 3km)
        val longMaxKm = (absLongMax * lvlAdj).roundToInt().coerceIn(3, 32)

        // 롱런 비율: 4주 패턴 (주1·주2·주3·주4)
        // 주4는 longArr[3]=0 이라 쓰이지 않음
        val longFactors: DoubleArray = when {
            racePhase == "TAPER" -> doubleArrayOf(0.20, 0.16, 0.12)
            racePhase == "FINAL" -> doubleArrayOf(0.14, 0.10, 0.08)
            racePhase == "RACE"  -> doubleArrayOf(0.08, 0.06, 0.05)
            // 풀코스 피크: 3주에 걸쳐 최장 거리로 올림
            racePhase == "PEAK" && goalKm >= 42.0 ->
                doubleArrayOf(0.46, 0.52, 0.58)
            // 풀코스 빌드업
            racePhase == "BUILD" && goalKm >= 42.0 ->
                doubleArrayOf(0.38, 0.43, 0.48)
            // 풀코스 베이스·일반
            goalKm >= 42.0 -> doubleArrayOf(0.33, 0.37, 0.40)
            // 하프 피크
            racePhase == "PEAK" && goalKm >= 21.0 ->
                doubleArrayOf(0.40, 0.46, 0.52)
            // 하프 빌드업
            racePhase == "BUILD" && goalKm >= 21.0 ->
                doubleArrayOf(0.35, 0.40, 0.45)
            // 하프 베이스·일반
            goalKm >= 21.0 -> doubleArrayOf(0.33, 0.37, 0.40)
            // 10K 이하
            eff == "높음"  -> doubleArrayOf(0.28, 0.32, 0.36)
            eff == "보통"  -> doubleArrayOf(0.30, 0.34, 0.37)
            else           -> doubleArrayOf(0.33, 0.36, 0.38)
        }
        // 롱런은 주간 거리 대비 최대 38%, 절대 상한 longMaxKm
        // 첫째 주 시작 월의 계절 계수를 롱런에도 반영
        val scLong = if (UserProfile.data.seasonAware) seasonCoeff(firstMonday.monthValue, racePhase) else 1.0
        val longArr = intArrayOf(
            (base * longFactors[0] * lvlAdj * scLong).roundToInt().coerceIn(3, longMaxKm),
            (base * longFactors[1] * lvlAdj * scLong).roundToInt().coerceIn(3, longMaxKm),
            (base * longFactors[2] * lvlAdj * scLong).roundToInt().coerceIn(3, longMaxKm),
            0,
        )        // 이지런 한 번의 거리 범위 (평소 주간 거리의 약 13~28%)
        val minEasy = maxOf(2.0, base * 0.13)
        val maxEasy = maxOf(minEasy + 1.0, base * 0.28)
        val phases = when (racePhase) {
            "BASE"  -> arrayOf("기초", "기초", "기초", "점검")
            "BUILD" -> arrayOf("빌드업", "빌드업", "상승", "점검")
            "PEAK"  -> arrayOf("피크", "피크", "피크", "점검")
            "TAPER" -> arrayOf("테이퍼", "테이퍼", "테이퍼", "테이퍼")
            "FINAL" -> arrayOf("최종조정", "최종조정", "최종조정", "최종조정")
            "RACE"  -> arrayOf("대회주", "대회주", "대회주", "대회주")
            else    -> arrayOf("재적응", "재개", "상승", "점검")
        }
        val anchorUsed = anchor ?: firstMonday   // 처음 만드는 계획이면 이 계획의 첫 주가 1주차
        // 훈련 4주(한 사이클)마다 한 단계씩 올라간다. 훈련 시작 주 기준이라 몇 번을 다시 계획해도 같은 값이다.
        fun stageFor(monday: LocalDate): Int {
            if (!canProgress) return 0
            val calendarStage = ((weekNumber(anchorUsed, monday) - 1) / 4).coerceAtLeast(0)
            // 최대 5단계. 상승 조건 충족이면 한 단계 위, 유지면 그대로, 하향이면 한 단계 아래
            return when (gate) {
                ProgGate.UP -> minOf(calendarStage, prevStage + 1, 5)
                ProgGate.HOLD -> minOf(calendarStage, prevStage, 5)
                ProgGate.DOWN -> maxOf(prevStage - 1, 0)
            }
        }

        // 첫 주가 이번 주라면 이미 달린 거리는 목표에서 뺀다
        val alreadyThisWeek = sorted.filter { it.date >= firstMonday }.sumOf { it.distanceKm }
        val items = mutableListOf<PlanItem>()
        val weekTargets = DoubleArray(4)
        for (w in 0 until 4) {
            val monday = firstMonday.plusDays(7L * w)
            val label = weekLabel(anchorUsed, monday, phases[w])
            val sc = if (UserProfile.data.seasonAware) seasonCoeff(monday.monthValue, racePhase) else 1.0
            val target = if (w == 0) (base * mult[0] * sc - alreadyThisWeek).coerceAtLeast(base * 0.25) else base * mult[w] * sc
            weekTargets[w] = target
            val isTest = w == 3
            val phaseNoQ = racePhase == "TAPER" || racePhase == "FINAL" || racePhase == "RACE" || racePhase == "BASE"
            val hasQ = if (phaseNoQ) false else (w != 0 || eff == "낮음")
            val isTempo = w == 2
            val stage = stageFor(monday)
            val repsBase = when (w) {
                0 -> 5
                1 -> if (eff == "높음") 4 else 5
                3 -> 3
                else -> 4
            }
            val reps = if (w == 3) repsBase else minOf(repsBase + repsAddFor(stage), maxReps, intervalRepsCap)
            val tp = tempoPlanFor(tempoMinFor(stage), base, tempoPace, maxTempoMin)
            val tempoMin = tp.totalMin
            val tempoPaceW = maxOf((if (tp.reps == 1) tempoPaceFor(tempoMin) else tempoPace) + tpAdj, goal + 10)   // 크루즈는 T 페이스 그대로
            val restTxt = if (tp.restSec >= 120) "2분" else "1분"
            val longRep = if (isHalfFull) longRepeatFor(if (w == 3) 0 else stage, isFull, repeatCapKm) else null
            val repPace = repPaceHF + ivAdj
            val warmKm = 720.0 / easy                 // 웜업 12분
            val coolKm = 600.0 / (easy + 15.0)        // 쿨다운 10분
            val qKm = when {
                !hasQ -> 0.0
                isTempo -> Math.round((warmKm + tempoMin * 60.0 / tempoPaceW + (tp.reps - 1) * tp.restSec / (easy + 20.0) + coolKm) * 10) / 10.0
                longRep != null -> Math.round((warmKm + longRep.n * longRep.km + (longRep.n - 1) * 0.35 + coolKm) * 10) / 10.0
                else -> Math.round((warmKm + reps + (reps - 1) * 0.3 + coolKm) * 10) / 10.0
            }
            val longKm = if (isTest) 10.0 else longArr[w].toDouble()
            val remaining = target - longKm - qKm

            val easyTips = listOf("페이스 $easyTxt, 심박 ${UserProfile.EASY_HR} 이하", "끝까지 일정하게, 후반 가속 금지", "케이던스는 평소 리듬 그대로")
            val stridesTips = easyTips + "끝나기 전에 가볍게 스트라이드 4회 (약 20초)"

            if (hasQ) {
                if (isTempo) {
                    items += PlanItem(
                        date = monday.plusDays(1), week = label, type = WorkoutType.TEMPO,
                        title = if (tp.reps == 1) "템포 ${tempoMin}분" else "크루즈 템포 ${tp.reps}×${tp.segMin}분",
                        km = qKm, optional = false,
                        tips = if (tp.reps == 1) listOf(
                            "웜업 12분 @${formatPace(easy.toDouble())}",
                            "본운동 ${tempoMin}분을 ${formatPace(tempoPaceW.toDouble())}로 일정하게 (심박 148~158)",
                            "쿨다운 10분 + 스트레칭",
                            "짧은 말 몇 마디는 할 수 있는 강도",
                        ) else listOf(
                            "웜업 12분 @${formatPace(easy.toDouble())}",
                            "${tp.segMin}분 × ${tp.reps}회를 ${formatPace(tempoPaceW.toDouble())}로 (합계 ${tempoMin}분), 사이 ${restTxt} 아주 천천히 조깅",
                            "쿨다운 10분 + 스트레칭",
                            "쉬는 시간이 짧아요. 구간 페이스는 끝까지 일정하게",
                        ),
                        targetPace = tempoPaceW, reps = if (tp.reps > 1) tp.reps else 0, stage = stage, paceAdj = tpAdj,
                    )
                } else {
                    items += PlanItem(
                        date = monday.plusDays(1), week = label, type = WorkoutType.INTERVAL,
                        title = if (longRep != null) "${if (isFull) "마라톤 페이스" else "하프 페이스"} ${kmTxt(longRep.km)}km×${longRep.n}" else "인터벌 1km×$reps",
                        km = qKm, optional = false,
                        tips = if (longRep != null) listOf(
                            "웜업 12분 @${formatPace(easy.toDouble())} + 스트라이드",
                            "${kmTxt(longRep.km)}km × ${longRep.n}회 @${formatPace(repPace.toDouble())} (합계 ${kmTxt(longRep.n * longRep.km)}km), 사이 2분 조깅",
                            "쿨다운 10분 후 스트레칭",
                            if (isFull) "마라톤 페이스 감각을 익히는 훈련이에요. 호흡이 편해야 해요"
                            else "하프 페이스 부근의 '편안하게 힘든' 강도예요. 숨이 완전히 차오르면 너무 빠른 거예요",
                        ) else listOf(
                            "웜업 12분 @${formatPace(easy.toDouble())} + 스트라이드",
                            "1km × ${reps}회 @${formatPace(intervalPaceA.toDouble())}, 사이 2분 조깅",
                            "쿨다운 10분 후 스트레칭",
                            "첫 반복은 일부러 조금 느리게",
                        ),
                        targetPace = if (longRep != null) repPace else intervalPaceA,
                        reps = if (longRep != null) longRep.n else reps, stage = stage, paceAdj = ivAdj,
                    )
                }
                // 주 5회 이상이면 이지런을 수요일·목요일 두 날로 쪼개서 하루 부담을 줄인다
                if (UserProfile.RUN_DAYS >= 5 && !isTest && remaining >= minEasy * 2) {
                    val a = (remaining * 0.5).coerceIn(minEasy, maxEasy).roundToInt()
                    val b = (remaining - a).coerceIn(minEasy, maxEasy).roundToInt()
                    items += PlanItem(
                        date = monday.plusDays(2), week = label, type = WorkoutType.EASY,
                        title = "이지런 ${a}km", km = a.toDouble(), optional = false,
                        tips = easyTips, targetPace = easy,
                    )
                    items += PlanItem(
                        date = monday.plusDays(3), week = label, type = WorkoutType.EASY,
                        title = "이지런 ${b}km", km = b.toDouble(), optional = false,
                        tips = easyTips, targetPace = easy,
                    )
                } else {
                    val e = remaining.coerceIn(minEasy, if (isTest) minEasy + 1.0 else maxEasy).roundToInt()
                    items += PlanItem(
                        date = monday.plusDays(3), week = label, type = WorkoutType.EASY,
                        title = "이지런 ${e}km", km = e.toDouble(), optional = false,
                        tips = easyTips, targetPace = easy,
                    )
                }
            } else {
                val a = (remaining * 0.45).coerceIn(minEasy, maxEasy * 0.9).roundToInt()
                val b = (remaining - a).coerceIn(minEasy, maxEasy).roundToInt()
                items += PlanItem(
                    date = monday.plusDays(1), week = label, type = WorkoutType.EASY,
                    title = "이지런 ${a}km", km = a.toDouble(), optional = false,
                    tips = easyTips, targetPace = easy,
                )
                items += PlanItem(
                    date = monday.plusDays(3), week = label, type = WorkoutType.EASY,
                    title = "이지런 ${b}km + 스트라이드", km = b.toDouble(), optional = false,
                    tips = stridesTips, targetPace = easy,
                )
            }

            if (isTest) {
                items += PlanItem(
                    date = monday.plusDays(5), week = label, type = WorkoutType.TEST,
                    title = "10K 기록 측정", km = 10.0, optional = false,
                    tips = listOf(
                        "첫 1km는 ${formatPace(testPace + 8.0)} 안팎으로 절제",
                        "2~8km 일정하게, 마지막 2km에서 여력만큼",
                        "전날 휴식, 전 2주간 퀄리티를 지킨 컨디션에서 도전",
                    ),
                    targetPace = testPace.toInt(),
                )
            } else {
                val lt = mutableListOf("$easyTxt 로 끝까지 일정하게", "6~8km 지점에서 물 마시기",
                    if (UserProfile.RUN_DAYS >= 6) "전날 짧게 뛰고 충분히 자면 OK" else "전날(금) 휴식 필수")
                if (w == 2) lt += "마지막 2km만 ${formatPace((easy - 20).toDouble())}로 올려도 좋아요"
                // 주 6회 이상이면 롱런 전날(금) 아주 짧은 활성화 이지런 추가 (선택)
                if (UserProfile.RUN_DAYS >= 6 && !isTest) {
                    val actKm = maxOf(2, (minEasy * 0.7).roundToInt())
                    items += PlanItem(
                        date = monday.plusDays(4), week = label, type = WorkoutType.EASY,
                        title = "이지런 ${actKm}km (선택)", km = actKm.toDouble(), optional = true,
                        tips = listOf("롱런 전날 다리를 깨우는 아주 짧은 러닝", "심박 ${UserProfile.EASY_HR - 10} 이하, 불편하면 걷기로"),
                        targetPace = easy,
                    )
                }
                items += PlanItem(
                    date = monday.plusDays(5), week = label, type = WorkoutType.LONG,
                    title = "롱런 ${longArr[w]}km", km = longArr[w].toDouble(), optional = false,
                    tips = lt, targetPace = easy,
                )
            }

            if (w >= 1 || eff != "높음") {   // 주 5회 이상이면 더 넓은 범위에서 선택 이지런 추가
                val optKm = minEasy.roundToInt().coerceAtLeast(2)
                val isSunMandatory = UserProfile.RUN_DAYS >= 7
                items += PlanItem(
                    date = monday.plusDays(6), week = label, type = WorkoutType.EASY,
                    title = if (isSunMandatory) "이지런 ${optKm}km" else "이지런 ${optKm}km (선택)",
                    km = optKm.toDouble(), optional = !isSunMandatory,
                    tips = listOf("아주 천천히, 심박 ${UserProfile.EASY_HR - 7} 이하", "무릎이 뻐근하거나 피곤하면 휴식·걷기로 대체"),
                    targetPace = easy,
                )
            }
        }
        val generatedAll = items.filter { it.date >= firstRun }.sortedBy { it.date }
        // 장거리·강한 러닝 후 72시간 안에는 인터벌·템포를 넣지 않는다
        val lastHeavy = sorted
            .filter { it.date >= today.minusDays(4) && (isHard(it) || it.distanceKm >= maxOf(8.0, kmBase * 0.40)) }
            .maxByOrNull { it.date }?.date
        val qEarliest = lastHeavy?.plusDays(3)
        val guarded = generatedAll.map { g ->
            if (qEarliest != null && g.date < qEarliest && (g.type == WorkoutType.INTERVAL || g.type == WorkoutType.TEMPO)) {
                val e = g.km.coerceAtMost(6.0).roundToInt().coerceAtLeast(4)
                PlanItem(
                    date = g.date, week = g.week, type = WorkoutType.EASY,
                    title = "이지런 ${e}km", km = e.toDouble(), optional = false,
                    tips = listOf("페이스 $easyTxt, 심박 ${UserProfile.EASY_HR} 이하", "직전 강한 러닝 회복 중이라 강도를 낮췄어요"),
                    targetPace = easy,
                )
            } else {
                g
            }
        }
        // 사용자가 직접 날짜를 옮긴 훈련은 그대로 두고, 같은 날이거나 같은 주의 같은 종류 훈련은 새로 만들지 않는다
        val pinned = oldPlan.filter { it.rescheduled && it.date >= earliest }
        fun weekOf(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value - 1).toLong())
        val qualityTypes = setOf(WorkoutType.INTERVAL, WorkoutType.TEMPO, WorkoutType.TEST, WorkoutType.LONG)
        val generated = guarded.filter { g ->
            pinned.none { p -> p.date == g.date || (g.type in qualityTypes && p.type == g.type && weekOf(p.date) == weekOf(g.date)) }
        }
        val futureAll = (pinned + generated).sortedBy { it.date }
        val kept = oldPlan.filter { it.date < earliest }
        val newPlan = relabelWeeks(dedupeIds(kept + futureAll), anchorUsed)

        // ── 바뀐 점 (오늘/내일부터 14일, 날짜별 비교) ──
        val changes = mutableListOf<String>()
        var d = earliest
        for (i in 0 until 14) {
            val o = oldPlan.firstOrNull { it.date == d }
            val n = futureAll.firstOrNull { it.date == d }
            when {
                o != null && n == null -> changes += "${dayLabel(d)} ${o.title} → 휴식"
                o == null && n != null -> changes += "${dayLabel(d)} (계획 없음) → ${n.title}"
                o != null && n != null && (o.type != n.type || Math.abs(o.km - n.km) >= 0.5) ->
                    changes += "${dayLabel(d)} ${o.title} → ${n.title}"
            }
            d = d.plusDays(1)
        }
        fun weekSum(list: List<PlanItem>, monday: LocalDate): Double =
            list.filter { !it.optional && it.date >= monday && it.date <= monday.plusDays(6) && it.date >= earliest }.sumOf { it.km }
        for (w in 0 until 2) {
            val monday = firstMonday.plusDays(7L * w)
            val oldKm = weekSum(oldPlan, monday)
            val newKm = weekSum(futureAll, monday)
            if (Math.abs(oldKm - newKm) >= 1.0) {
                changes += "${monday.monthValue}/${monday.dayOfMonth} 주 계획 거리 ${"%.0f".format(oldKm)}km → ${"%.0f".format(newKm)}km"
            }
        }
        if (pinned.isNotEmpty()) {
            changes += "직접 옮긴 훈련 ${pinned.size}개는 그대로 뒀어요: " + pinned.joinToString(", ") { "${dayLabel(it.date)} ${it.title}" }
        }
        if (changes.isEmpty()) changes += "기존 계획과 거의 같아요 (피로 신호가 크지 않아 그대로 이어가요)"

        // ── 판단 근거 ──
        val facts = mutableListOf<String>()
        // 판단 근거: 롱런 계획 요약
        facts += "롱런 계획: ${longArr[0]}→${longArr[1]}→${longArr[2]}km (상한 ${longMaxKm}km · 최근 최장 ${recentLongRun.roundToInt()}km 기준)"
        facts += if (qWindow.size < 2) {
            "지난 4주 퀄리티 훈련 계획이 ${qWindow.size}회뿐이라 수행 기록 없이 진행해요"
        } else {
            "지난 4주 퀄리티 훈련 ${qDone}/${qWindow.size}회 수행" +
                (if (qScores.isNotEmpty()) " · 평균 ${qAvgScore.roundToInt()}점" else "") + " → " +
                (when (gate) { ProgGate.UP -> "상승 조건 충족"; ProgGate.HOLD -> "단계 유지"; ProgGate.DOWN -> "한 단계 하향" })
        }
        // 실력 기준 페이스: 새 방식과 지금 방식을 같이 보여준다
        if (level != "낮음") {
            facts += "쉬는 날 권장: 스트레칭·요가·가벼운 걷기처럼 부담이 적은 활동은 해도 좋아요. 크로스 트레이닝에 기록하면 피로도에 반영돼요 (환산이 큰 활동은 부하가 늘어요)"
        }
        if (crossEq7 > 0.05) {
            facts += "크로스 트레이닝 부하: 최근 7일 러닝 환산 +${"%.1f".format(crossEq7)}km (피로도·최근 부하 계산에 반영)"
        }
        if (anchorNow != null) {
            facts += "실력 기준 페이스: 지금 방식 ${if (refPace != null) formatPace(refPace) else "-"} · 새 방식 ${formatPace(anchorNow)} (최고 8km 연속 구간)" +
                (if (useNew) " → 새 방식으로 계획 중" else " → 비교용, 계획은 지금 방식")
        }
        fun sgn(x: Int) = if (x > 0) "+$x" else "$x"
        facts += "목표 페이스: $ivLabel · 템포 ${formatPace((tempoPace + tpAdj).toDouble())}" +
            (if (ivAdj != 0 || tpAdj != 0) " (소화한 결과로 보정: 인터벌 ${sgn(ivAdj)}초 · 템포 ${sgn(tpAdj)}초)" else "")
        if (canProgress) {
            val st = stageFor(firstMonday)
            val tpS = tempoPlanFor(tempoMinFor(st), base, tempoPace, maxTempoMin)
            val tempoTxt = if (tpS.reps == 1) "템포 ${tpS.totalMin}분" else "크루즈 템포 ${tpS.reps}×${tpS.segMin}분"
            val ivTxt = if (isHalfFull) {
                val lr = longRepeatFor(st, isFull, repeatCapKm)
                "${if (isFull) "마라톤 페이스" else "하프 페이스"} ${kmTxt(lr.km)}km×${lr.n}"
            } else {
                "인터벌 최대 1km×${minOf(5 + repsAddFor(st), maxReps, intervalRepsCap)}"
            }
            facts += "점진 상승 중 (단계 $st) · $ivTxt · $tempoTxt · 기준 주간 거리 ${"%.0f".format(kmBase)}→${"%.0f".format(base)}km"
            if (!isHalfFull) {
                facts += "인터벌 상한 ${intervalRepsCap}회 (주간 거리의 ${(INTERVAL_VOL_PCT * 100).roundToInt()}%, 최소 ${INTERVAL_MIN_REPS}회) · 종목 상한 ${maxReps}회 — 주간 거리가 늘수록 더 늘어요"
            }
            facts += "템포 상한 ${maxOf(20, tempoVolCapMin / 5 * 5)}분 (주간 거리의 ${(TEMPO_VOL_PCT * 100).roundToInt()}%) · 종목 상한 ${maxTempoMin}분 — 주간 거리가 늘수록 더 길어져요"
            if (improved && trendNow != null && prevRef != null) {
                facts += "실력 기준 페이스 ${formatPace(trendNow)} (4주 전 ${formatPace(prevRef)}) → 계획 페이스에 반영"
            }
        } else {
            val why = mutableListOf<String>()
            if (last28.size < minRuns) why += "최근 4주 러닝 ${last28.size}회 (기준 ${minRuns}회)"
            if (eff == "높음") why += "피로도 높음"
            if (acwr > 1.35) why += "최근 부하 급증"
            if (declining) why += "최근 페이스 하락"
            if (peakPhase) why += "대회 직전 조정 기간"
            if (why.isNotEmpty()) facts += "점진 상승 보류: " + why.joinToString(", ")
        }
        if (rd != null && weeksToRace != null) {
            facts += "대회 날짜 ${rd} (${weeksToRace}주 남음) · 훈련 단계: ${racePhaseLabel(weeksToRace, if (isLongGoal) "풀" else "하프")}"
        }
        val lvlLabel = when (UserProfile.data.intensityLevel) { -1 -> "여유롭게"; 1 -> "도전적으로"; else -> "기본" }
        if (UserProfile.data.intensityLevel != 0) facts += "훈련 강도: $lvlLabel (롱런·주간 거리 조정 반영)"
        if (UserProfile.data.seasonAware) {
            val sc0 = seasonCoeff(firstMonday.monthValue, racePhase)
            facts += "${seasonLabel(firstMonday.monthValue)} 계절 반영 (계수 ${"%.2f".format(sc0)})"
        }
        if (UserProfile.data.jointCare) facts += "관절 주의 ON → 롱런 10% 감소 적용"
        facts += "최근 7일 러닝 ${"%.1f".format(runKm7)}km" +
            (if (walkEq7 > 0.05) " + 걷기 환산 ${"%.1f".format(walkEq7)}km" else "") +
            " — 지난 4주 주간 평균 ${"%.1f".format(chronic)}km의 ${pctText(acwr)}"
        walkSummary(walks, today)?.let { facts += "최근 7일 걷기 $it" }
        facts += "이번 주(월~오늘) ${"%.1f".format(weekKm)}km · ${weekRuns.size}회"
        if (ranToday) {
            val t = sorted.last { it.date == today }
            facts += "오늘 ${"%.2f".format(t.distanceKm)}km · 평균 ${formatPace(t.paceSecPerKm)}" +
                (t.avgHr?.let { " · ♥$it" } ?: "")
        } else if (lastRun != null) {
            facts += "마지막 러닝 ${dayLabel(lastRun.date)} ${"%.1f".format(lastRun.distanceKm)}km"
        }
        hardRecent.takeLast(2).forEach {
            facts += "고강도 러닝 ${dayLabel(it.date)} ${"%.1f".format(it.distanceKm)}km · ${formatPace(it.paceSecPerKm)} · ♥${it.avgHr ?: "-"}(최대 ${it.maxHr ?: "-"})"
        }
        if (consecutive >= 2) facts += "연속 러닝 ${consecutive}일"

        // ── 바꾼 이유 ──
        val w1 = "%.0f".format(base * mult[0])
        val reasons = mutableListOf<String>()
        if (UserProfile.data.seasonAware) {
            val seasonT = seasonTip(firstMonday.monthValue)
            if (seasonT.isNotEmpty()) reasons += seasonT
        }
        if (acwr >= 1.3) {
            reasons += "부하가 급하게 올랐어요. 최근 7일 ${"%.0f".format(acute)}km는 평소(주 ${"%.0f".format(chronic)}km)의 ${pctText(acwr)}예요. " +
                "평소의 130%를 넘는 급증은 무릎·정강이 부상이 늘어나는 구간이고, " + (if (UserProfile.bmi >= 25.0) "체중 ${UserProfile.WEIGHT_KG}kg이면 착지 충격도 커서 " else "") +
                "첫 주 거리를 평소의 ${pctText(mult[0])}인 ${w1}km로 낮췄어요."
        } else if (acwr >= 1.15) {
            reasons += "최근 7일 부하가 평소의 ${pctText(acwr)}로 올라가는 중이에요. 급증 직전이라 첫 주를 평소의 ${pctText(mult[0])}(${w1}km)로 조절했어요."
        } else {
            reasons += "최근 7일 부하(${"%.0f".format(acute)}km)가 평소 범위(주 ${"%.0f".format(chronic)}km) 안이에요. 주간 거리는 평소 대비 ${pctText(mult[0])}로 시작해 서서히 늘려요."
        }
        if (restDays > 0) {
            val why = if (adv.reasons.isNotEmpty()) adv.reasons.joinToString(" · ") else "피로도가 높아요"
            reasons += "$why. 강한 러닝 뒤에는 근육·건 회복에 보통 48~72시간이 필요해서 " +
                "${restDays}일은 쉬고 ${dayLabel(firstRun)}부터 이지런으로 다시 시작해요."
        }
        if (eff != "낮음") {
            reasons += "이번 계획의 첫 주에는 인터벌·템포를 넣지 않고 이지런과 가벼운 스트라이드로 몸을 적응시켜요. 둘째 주에 인터벌(${if (eff == "높음") 4 else 5}회)로 재개하고, " +
                "셋째 주 템포, 넷째 주에는 인터벌을 줄이고 10K 기록으로 회복 상태를 점검해요."
        }
        if (longRecent >= 10 || eff != "낮음") {
            reasons += "최근 긴 거리를 달렸기 때문에 롱런은 ${longArr[0]}km로 줄이고, 이후 ${longArr[1]} → ${longArr[2]}km로 천천히 올려요. 한 번에 1~2km씩만 늘리는 게 무릎에 안전해요."
        }
        reasons += "페이스는 최근 4주 중 가장 빠른 10K 근처 기록(${formatPace(ref)})을 기준으로 맞췄어요: " +
            "템포 ${formatPace((tempoPace + tpAdj).toDouble())}, ${ivLabel}, 이지런 ${easyTxt}. " +
            "목표(${formatPace(goal.toDouble())}) 기준으로 잡으면 현재 체력보다 빨라서 과훈련 위험이 커져요."

        if (readiness != null) {
            val bad = readiness.parts.filter { it.penalty > 0 && it.name != FatigueMath.LOAD_PART }
                .joinToString(", ") { "${it.name} ${it.value}" }
            facts += "오늘 컨디션 ${readiness.score}점(${readiness.level})" + (if (bad.isNotEmpty()) " · $bad" else "")
            facts += "피로도 $score = 러닝 $runningScore + 건강 신호 $healthAdd"
            if (UserProfile.data.intensityLevel == -1) {
                reasons += "훈련 강도를 '여유롭게'로 설정해서 롱런과 주간 거리를 20% 줄였어요. 계획이 적당히 느껴지면 '기본'으로 올려 보세요."
            }
        if (healthAdd > 0) {
                reasons += "건강 신호(수면·심박·에너지 등)가 평소보다 좋지 않아요(건강 신호 감점 ${readiness.healthPenalty}점). 회복이 덜 된 상태에서 강한 훈련을 쌓으면 " +
                    "부상 위험이 커져서 피로도를 ${healthAdd}점 더 높게 반영하고, 그만큼 휴식과 이지런을 늘렸어요. 러닝 부하는 이미 러닝 피로도에 들어 있어서 여기서 다시 더하지 않았어요."
            }
        }

        val pinnedEarly = pinned.filter { it.date < firstRun }
        if (pinnedEarly.isNotEmpty()) {
            reasons += "직접 옮긴 훈련(${pinnedEarly.joinToString(", ") { dayLabel(it.date) + " " + it.title }})이 회복 기간 안에 있어요. " +
                "그대로 두었지만 강도를 낮추거나 하루 더 미루는 걸 권해요."
        }

        val now = java.time.LocalDateTime.now()
        val swLevel = if (UserProfile.data.seasonAware) seasonWarnLevel(firstMonday.monthValue) else null
        val swLine: String? = if (swLevel != null) seasonTip(firstMonday.monthValue).lines().firstOrNull()?.removePrefix("🌡️ ")?.removePrefix("🧊 ")?.removePrefix("🍂 ")?.trim() else null
        val note = PlanNote(
            createdAt = "${now.monthValue}/${now.dayOfMonth} %02d:%02d".format(now.hour, now.minute),
            score = score,
            level = level,
            facts = facts,
            changes = changes.take(10),
            reasons = reasons,
            seasonWarning = swLine,
            seasonWarnLevel = swLevel,
        )
        return PlanUpdate(newPlan, note, anchorUsed)
    }
}
