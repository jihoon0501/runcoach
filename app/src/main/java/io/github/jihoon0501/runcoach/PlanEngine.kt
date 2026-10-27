package io.github.jihoon0501.runcoach

import java.time.LocalDate
import java.time.YearMonth
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
    val runDays: Int = 4,            // 주당 러닝 횟수 선호 (3 또는 4)
    val weeklyKm: Int = 15,          // 요즘 일주일에 달리는 거리 (기록이 부족할 때 기준)
    val jointCare: Boolean = true,   // 무릎·관절 부담을 줄이는 쪽을 선호
    val onboarded: Boolean = false,
)

/** 앱 전체가 참조하는 현재 사용자 설정. 값은 [ProfileData]에서 오고, 심박 기준 등은 여기서 계산한다. */
object UserProfile {
    var data: ProfileData = ProfileData()

    /** 목표 기록이 없을 때, 최근 기록으로 정한 기준 페이스 (10K 환산, 초/km). 0이면 아직 없음 */
    var autoGoalPace: Int = 0

    val NAME: String get() = data.nickname.ifBlank { "러너" }
    val HEIGHT_CM: Int get() = data.heightCm
    val WEIGHT_KG: Int get() = data.weightKg
    val RUN_DAYS: Int get() = data.runDays.coerceIn(3, 4)
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
        append("목표: ${goalLabel()}. 주 ${RUN_DAYS}회 이내로 달리는 걸 선호함. ")
        append("최대 심박 약 ${MAX_HR}bpm. ")
        if (data.jointCare) append("무릎·관절 부담을 줄이는 쪽을 선호하고, 강한 훈련 사이에 휴식이 필요함.")
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
)

class PlanUpdate(val newPlan: List<PlanItem>, val note: PlanNote)

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
            val cand = runs
                .filter { it.date == p.date && it.start.toEpochMilli() !in used && it.distanceKm >= p.km * 0.5 }
                .maxByOrNull { it.distanceKm } ?: continue
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
                val core = if (splits.size > 4) splits.drop(1).dropLast(1) else splits
                val avg = if (core.isNotEmpty()) core.average() else pace
                val diff = avg - target
                intensity -= abs(diff) * 3.0
                points += "템포 구간 평균 ${formatPace(avg)} (목표 ${formatPace(target.toDouble())})"
                extra = if (diff > 8) "목표보다 느렸어요." else if (diff < -8) "목표보다 빨랐어요." else "목표 페이스에 잘 맞았어요."
            }
            WorkoutType.INTERVAL -> {
                val reps = repsOf(p)
                val segs = r.segments.filter { it.role == SegRole.MAIN && it.kind == SegKind.RUN && it.durSec in 120..420 }
                if (segs.size >= 2) {
                    val avg = segs.map { it.paceSecPerKm }.filter { it > 0 }.average()
                    val cnt = min(segs.size, reps)
                    points += "빠른 반복 ${segs.size}회 감지 (계획 ${reps}회)"
                    points += "반복 평균 ${formatPace(avg)} (목표 ${formatPace(target.toDouble())})"
                    intensity = cnt.toDouble() / reps * 60.0 + (40.0 - abs(avg - target) * 2.0).coerceIn(0.0, 40.0)
                } else {
                    val near = splits.count { it <= target + 15 }
                    points += "목표 페이스 근처 구간 ${near}개 (계획 ${reps}회)"
                    intensity = (min(near, reps).toDouble() / reps * 100.0)
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
            reasons += "최근 6일 중 ${runDays}일을 달렸어요 (주 ${UserProfile.RUN_DAYS}회 이내가 회복에 안전해요)"
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

    private fun isHard(r: RunSession): Boolean {
        val pace = r.paceSecPerKm
        return r.distanceKm >= 4.0 && ((r.avgHr ?: 0) >= UserProfile.HARD_HR || (pace > 0 && pace <= UserProfile.GOAL_10K_PACE + 25.0))
    }

    /** 러닝 기록만으로 계산한 피로도 점수(0~100). [replan]과 같은 규칙이다. */
    fun fatigueScore(runs: List<RunSession>, today: LocalDate, walks: List<WalkSession> = emptyList()): Int {
        val sorted = runs.filter { it.date <= today }.sortedBy { it.start }
        if (sorted.isEmpty()) return 0
        val ranToday = sorted.any { it.date == today }
        val last7 = sorted.filter { it.date >= today.minusDays(6) }
        val last28 = sorted.filter { it.date >= today.minusDays(27) }
        val runKm7 = last7.sumOf { it.distanceKm }
        val walkEq7 = walkEq(walks, today, 7)
        val acute = runKm7 + walkEq7
        val chronic = (last28.sumOf { it.distanceKm } + walkEq(walks, today, 28)) / 4.0
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


    private fun round5(v: Double): Int = (v / 5.0).roundToInt() * 5

    private fun dayLabel(d: LocalDate): String = "${d.monthValue}/${d.dayOfMonth}(${korDow(d.dayOfWeek)})"

    private fun pctText(v: Double): String = "${Math.round(v * 100)}%"

    /**
     * 최근 러닝(거리·강도·연속일)으로 피로도를 계산하고, 그에 맞춰 앞으로 4주 계획을 새로 만든다.
     * 지나간 계획은 그대로 두고, 오늘(또는 내일)부터의 계획만 바꾼다. 왜 바꿨는지는 [PlanUpdate.note]에 담긴다.
     */
    fun replan(runs: List<RunSession>, oldPlan: List<PlanItem>, today: LocalDate, readiness: Readiness? = null, walks: List<WalkSession> = emptyList()): PlanUpdate {
        val sorted = runs.filter { it.date <= today }.sortedBy { it.start }
        val ranToday = sorted.any { it.date == today }
        val last7 = sorted.filter { it.date >= today.minusDays(6) }
        val last28 = sorted.filter { it.date >= today.minusDays(27) }
        val runKm7 = last7.sumOf { it.distanceKm }
        val walkEq7 = walkEq(walks, today, 7)
        val acute = runKm7 + walkEq7
        val chronic = (last28.sumOf { it.distanceKm } + walkEq(walks, today, 28)) / 4.0
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
        val runs4d = sorted.count { it.date >= today.minusDays(3) }

        // ── 피로도 점수 ──
        var score = 0
        score += when {
            acwr >= 1.5 -> 40
            acwr >= 1.3 -> 28
            acwr >= 1.15 -> 12
            else -> 0
        }
        score += when {
            weekKm >= kmBase * 1.5 -> 15
            weekKm >= kmBase * 1.2 -> 10
            weekKm >= kmBase * 0.95 -> 4
            else -> 0
        }
        score += minOf(hardRecent.size * 18, 36)
        score += when {
            longRecent >= kmBase * 0.50 -> 25
            longRecent >= kmBase * 0.40 -> 20
            longRecent >= kmBase * 0.33 -> 10
            else -> 0
        }
        score += when {
            consecutive >= 4 -> 20
            consecutive == 3 -> 12
            consecutive == 2 -> 6
            else -> 0
        }
        if (runs4d >= 3) score += 8
        // 수면·심박·에너지 점수 같은 건강 신호가 나쁘면 회복이 덜 된 상태라서 피로도를 더 높게 본다
        val healthAdd = when {
            readiness == null -> 0
            readiness.score < 40 -> 20
            readiness.score < 55 -> 12
            readiness.score < 70 -> 5
            else -> 0
        }
        score += healthAdd
        score = score.coerceIn(0, 100)
        val level = when {
            score >= 70 -> "높음"
            score >= 45 -> "보통"
            else -> "낮음"
        }
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
        val ref = refPace ?: (UserProfile.GOAL_10K_PACE + 17).toDouble()
        val goal = UserProfile.GOAL_10K_PACE
        val tempoPace = round5(maxOf(ref + 12.0, (goal + 10).toDouble()))
        val intervalPace = round5(maxOf(ref - 12.0, (goal - 5).toDouble()))
        val easy = easyPaceOf(last28)
        val easyTxt = "${formatPace((easy - 5).toDouble())}~${formatPace((easy + 10).toDouble())}"

        // ── 주차별 목표 거리/롱런 ──
        val mult = when (eff) {
            "높음" -> doubleArrayOf(0.70, 0.90, 1.00, 0.85)
            "보통" -> doubleArrayOf(0.85, 1.00, 1.05, 0.85)
            else -> doubleArrayOf(1.00, 1.05, 1.10, 0.85)
        }
        val base = kmBase.coerceAtLeast(5.0)
        // 롱런 거리는 평소 주간 거리에 비례 (주간 거리의 약 30~48%)
        val longFactors = when (eff) {
            "높음" -> doubleArrayOf(0.30, 0.37, 0.40)
            "보통" -> doubleArrayOf(0.34, 0.40, 0.44)
            else -> doubleArrayOf(0.40, 0.44, 0.48)
        }
        val longArr = intArrayOf(
            (base * longFactors[0]).roundToInt().coerceIn(3, 24),
            (base * longFactors[1]).roundToInt().coerceIn(3, 24),
            (base * longFactors[2]).roundToInt().coerceIn(3, 24),
            0,
        )
        // 이지런 한 번의 거리 범위 (평소 주간 거리의 약 13~28%)
        val minEasy = maxOf(2.0, base * 0.13)
        val maxEasy = maxOf(minEasy + 1.0, base * 0.28)
        val labels = arrayOf("1주차 · 재적응", "2주차 · 재개", "3주차 · 상승", "4주차 · 점검")

        // 첫 주가 이번 주라면 이미 달린 거리는 목표에서 뺀다
        val alreadyThisWeek = sorted.filter { it.date >= firstMonday }.sumOf { it.distanceKm }
        val items = mutableListOf<PlanItem>()
        val weekTargets = DoubleArray(4)
        for (w in 0 until 4) {
            val monday = firstMonday.plusDays(7L * w)
            val label = labels[w]
            val target = if (w == 0) (base * mult[0] - alreadyThisWeek).coerceAtLeast(base * 0.25) else base * mult[w]
            weekTargets[w] = target
            val isTest = w == 3
            val hasQ = w != 0 || eff == "낮음"
            val isTempo = w == 2
            val reps = when (w) {
                0 -> 5
                1 -> if (eff == "높음") 4 else 5
                3 -> 3
                else -> 4
            }
            val warmKm = 720.0 / easy                 // 웜업 12분
            val coolKm = 600.0 / (easy + 15.0)        // 쿨다운 10분
            val qKm = when {
                !hasQ -> 0.0
                isTempo -> Math.round((warmKm + 1200.0 / tempoPace + coolKm) * 10) / 10.0
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
                        title = "템포 20분", km = qKm, optional = false,
                        tips = listOf(
                            "웜업 12분 @${formatPace(easy.toDouble())}",
                            "본운동 20분을 ${formatPace(tempoPace.toDouble())}로 일정하게 (심박 148~158)",
                            "쿨다운 10분 + 스트레칭",
                            "짧은 말 몇 마디는 할 수 있는 강도",
                        ),
                        targetPace = tempoPace,
                    )
                } else {
                    items += PlanItem(
                        date = monday.plusDays(1), week = label, type = WorkoutType.INTERVAL,
                        title = "인터벌 1km×$reps", km = qKm, optional = false,
                        tips = listOf(
                            "웜업 12분 @${formatPace(easy.toDouble())} + 스트라이드",
                            "1km × ${reps}회 @${formatPace(intervalPace.toDouble())}, 사이 2분 조깅",
                            "쿨다운 10분 후 스트레칭",
                            "첫 반복은 일부러 조금 느리게",
                        ),
                        targetPace = intervalPace, reps = reps,
                    )
                }
                val e = remaining.coerceIn(minEasy, if (isTest) minEasy + 1.0 else maxEasy).roundToInt()   // 기록 측정 주에는 목요일을 가볍게
                items += PlanItem(
                    date = monday.plusDays(3), week = label, type = WorkoutType.EASY,
                    title = "이지런 ${e}km", km = e.toDouble(), optional = false,
                    tips = easyTips, targetPace = easy,
                )
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
                        "첫 1km는 ${formatPace((ref + 8).toDouble())} 안팎으로 절제",
                        "2~8km 일정하게, 마지막 2km에서 여력만큼",
                        "전날 휴식, 전 2주간 퀄리티를 지킨 컨디션에서 도전",
                    ),
                    targetPace = ref.toInt(),
                )
            } else {
                val lt = mutableListOf("$easyTxt 로 끝까지 일정하게", "6~8km 지점에서 물 마시기", "전날(금) 휴식 필수")
                if (w == 2) lt += "마지막 2km만 ${formatPace((easy - 20).toDouble())}로 올려도 좋아요"
                items += PlanItem(
                    date = monday.plusDays(5), week = label, type = WorkoutType.LONG,
                    title = "롱런 ${longArr[w]}km", km = longArr[w].toDouble(), optional = false,
                    tips = lt, targetPace = easy,
                )
            }

            if ((w >= 2 || (w == 1 && eff != "높음")) && UserProfile.RUN_DAYS >= 4) {
                val optKm = minEasy.roundToInt().coerceAtLeast(2)
                items += PlanItem(
                    date = monday.plusDays(6), week = label, type = WorkoutType.EASY,
                    title = "이지런 ${optKm}km (선택)", km = optKm.toDouble(), optional = true,
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
        val newPlan = dedupeIds(kept + futureAll)

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
        if (acwr >= 1.3) {
            reasons += "부하가 급하게 올랐어요. 최근 7일 ${"%.0f".format(acute)}km는 평소(주 ${"%.0f".format(chronic)}km)의 ${pctText(acwr)}예요. " +
                "평소의 130%를 넘는 급증은 무릎·정강이 부상이 늘어나는 구간이고, " + (if (UserProfile.bmi >= 25.0) "체중 ${UserProfile.WEIGHT_KG}kg이면 착지 충격도 커서 " else "") +
                "1주차 거리를 평소의 ${pctText(mult[0])}인 ${w1}km로 낮췄어요."
        } else if (acwr >= 1.15) {
            reasons += "최근 7일 부하가 평소의 ${pctText(acwr)}로 올라가는 중이에요. 급증 직전이라 1주차를 평소의 ${pctText(mult[0])}(${w1}km)로 조절했어요."
        } else {
            reasons += "최근 7일 부하(${"%.0f".format(acute)}km)가 평소 범위(주 ${"%.0f".format(chronic)}km) 안이에요. 주간 거리는 평소 대비 ${pctText(mult[0])}로 시작해 서서히 늘려요."
        }
        if (restDays > 0) {
            val why = if (adv.reasons.isNotEmpty()) adv.reasons.joinToString(" · ") else "피로도가 높아요"
            reasons += "$why. 강한 러닝 뒤에는 근육·건 회복에 보통 48~72시간이 필요해서 " +
                "${restDays}일은 쉬고 ${dayLabel(firstRun)}부터 이지런으로 다시 시작해요."
        }
        if (eff != "낮음") {
            reasons += "1주차에는 인터벌·템포를 넣지 않고 이지런과 가벼운 스트라이드로 몸을 적응시켜요. 2주차에 인터벌(${if (eff == "높음") 4 else 5}회)로 재개하고, " +
                "3주차 템포, 4주차에는 인터벌을 줄이고 10K 기록으로 회복 상태를 점검해요."
        }
        if (longRecent >= 10 || eff != "낮음") {
            reasons += "최근 긴 거리를 달렸기 때문에 롱런은 ${longArr[0]}km로 줄이고, 이후 ${longArr[1]} → ${longArr[2]}km로 천천히 올려요. 한 번에 1~2km씩만 늘리는 게 무릎에 안전해요."
        }
        reasons += "페이스는 최근 4주 중 가장 빠른 10K 근처 기록(${formatPace(ref)})을 기준으로 맞췄어요: " +
            "템포 ${formatPace(tempoPace.toDouble())}, 인터벌 ${formatPace(intervalPace.toDouble())}, 이지런 ${easyTxt}. " +
            "목표(${formatPace(goal.toDouble())}) 기준으로 잡으면 현재 체력보다 빨라서 과훈련 위험이 커져요."

        if (readiness != null) {
            val bad = readiness.parts.filter { it.penalty > 0 && it.name != "러닝 부하" }
                .joinToString(", ") { "${it.name} ${it.value}" }
            facts += "오늘 컨디션 ${readiness.score}점(${readiness.level})" + (if (bad.isNotEmpty()) " · $bad" else "")
            if (healthAdd > 0) {
                reasons += "건강 신호가 평소보다 좋지 않아요(컨디션 ${readiness.score}점). 회복이 덜 된 상태에서 강한 훈련을 쌓으면 " +
                    "부상 위험이 커져서 피로도를 ${healthAdd}점 더 높게 반영하고, 그만큼 휴식과 이지런을 늘렸어요."
            }
        }

        val pinnedEarly = pinned.filter { it.date < firstRun }
        if (pinnedEarly.isNotEmpty()) {
            reasons += "직접 옮긴 훈련(${pinnedEarly.joinToString(", ") { dayLabel(it.date) + " " + it.title }})이 회복 기간 안에 있어요. " +
                "그대로 두었지만 강도를 낮추거나 하루 더 미루는 걸 권해요."
        }

        val now = java.time.LocalDateTime.now()
        val note = PlanNote(
            createdAt = "${now.monthValue}/${now.dayOfMonth} %02d:%02d".format(now.hour, now.minute),
            score = score,
            level = level,
            facts = facts,
            changes = changes.take(10),
            reasons = reasons,
        )
        return PlanUpdate(newPlan, note)
    }
}
