// 훈련 계획 엔진 시뮬레이션: 가상 러닝 기록 6주로 계획을 만들고 소화한 뒤 다시 만들어요 (시나리오 A·B·C·D·E·G)
package io.github.jihoon0501.runcoach

import java.time.LocalDate
import java.time.ZoneId

fun inst(d: LocalDate) = d.atTime(7, 0).atZone(ZoneId.systemDefault()).toInstant()
fun pf(x: Int) = formatPace(x.toDouble())

fun steady(d: LocalDate, km: Double, pace: Int, hr: Int?, splits: List<Int>? = null): RunSession {
    val dur = (km * pace).toLong()
    return RunSession(
        start = inst(d), durationSec = dur, distanceKm = km, avgHr = hr, maxHr = hr?.plus(10), cadence = 170,
        lapCount = 1, title = null, splits = splits ?: List(km.toInt()) { pace },
        mainKm = km, mainSec = dur,
        segments = listOf(RunSegment(SegRole.MAIN, SegKind.RUN, 0, dur, km, hr, 170)),
    )
}

fun intervalRun(d: LocalDate, reps: Int, repPace: Int, repHr: Int): RunSession {
    val segs = mutableListOf<RunSegment>()
    segs += RunSegment(SegRole.WARMUP, SegKind.JOG, 0, 720, 1.7, 120, 165)
    var t = 720L
    repeat(reps) { i ->
        segs += RunSegment(SegRole.MAIN, SegKind.RUN, t, repPace.toLong(), 1.0, repHr, 180); t += repPace
        if (i < reps - 1) { segs += RunSegment(SegRole.RECOVERY, SegKind.JOG, t, 120, 0.3, 135, 160); t += 120 }
    }
    segs += RunSegment(SegRole.COOLDOWN, SegKind.JOG, t, 600, 1.4, 125, 160)
    val dist = 1.7 + reps + 0.3 * (reps - 1) + 1.4
    val dur = t + 600
    return RunSession(
        start = inst(d), durationSec = dur, distanceKm = dist, avgHr = 150, maxHr = repHr + 8, cadence = 172,
        lapCount = segs.size, title = null, splits = List(dist.toInt()) { (dur / dist).toInt() },
        mainKm = dist, mainSec = dur, segments = segs,
    )
}

fun tempoRun(d: LocalDate, minutes: Int, pace: Int, hr: Int): RunSession {
    val mainSec = minutes * 60L
    val mainKm = mainSec.toDouble() / pace
    val segs = listOf(
        RunSegment(SegRole.WARMUP, SegKind.JOG, 0, 720, 1.7, 120, 165),
        RunSegment(SegRole.MAIN, SegKind.RUN, 720, mainSec, mainKm, hr, 176),
        RunSegment(SegRole.COOLDOWN, SegKind.JOG, 720 + mainSec, 600, 1.4, 125, 160),
    )
    val dist = 1.7 + mainKm + 1.4
    val dur = 720 + mainSec + 600
    return RunSession(
        start = inst(d), durationSec = dur, distanceKm = dist, avgHr = hr - 8, maxHr = hr + 8, cadence = 172,
        lapCount = 3, title = null, splits = List(dist.toInt()) { (dur / dist).toInt() },
        mainKm = dist, mainSec = dur, segments = segs,
    )
}

fun history(t0: LocalDate): List<RunSession> {
    val runs = mutableListOf<RunSession>()
    var d = t0.minusDays(42)
    while (d < t0) {
        when (d.dayOfWeek.value) {
            2 -> runs += steady(d, 7.0, 410, 138)
            4 -> runs += steady(d, 9.0, 395, 142)
            6 -> runs += steady(d, 12.0, 375, 148)
            7 -> runs += steady(d, 6.0, 420, 125)
        }
        d = d.plusDays(1)
    }
    // 가장 잘 달린 기록: 11/14처럼 11.8km 평균 5'16", 심박 147
    val bestDay = runs.filter { it.date.dayOfWeek.value == 6 && it.date <= t0.minusDays(14) }.maxOf { it.date }
    runs.removeAll { it.date == bestDay }
    runs += steady(bestDay, 11.8, 316, 147, listOf(351, 327, 312, 317, 311, 312, 311, 311, 306, 309, 308))
    return runs.sortedBy { it.start }
}

fun execute(plan: List<PlanItem>, until: LocalDate, skipQuality: Boolean, fasterBy: Int, hrLevel: Int = 165): List<RunSession> {
    val out = mutableListOf<RunSession>()
    for (p in plan.filter { it.date < until && !it.optional }) {
        val tp = PlanEngine.targetPace(p)
        when (p.type) {
            WorkoutType.INTERVAL -> if (!skipQuality) out += intervalRun(p.date, PlanEngine.repsOf(p), tp - fasterBy, hrLevel)
            WorkoutType.TEMPO -> if (!skipQuality) {
                val mins = Regex("(\\d+)분").find(p.title)?.groupValues?.get(1)?.toIntOrNull() ?: 20
                out += tempoRun(p.date, mins, tp - fasterBy, 150)
            }
            WorkoutType.TEST -> if (!skipQuality) out += steady(p.date, 10.0, tp, 170)
            WorkoutType.EASY, WorkoutType.LONG -> out += steady(p.date, p.km, if (p.targetPace > 0) p.targetPace + 5 else 420, 135)
        }
    }
    return out
}

fun printPlan(title: String, plan: List<PlanItem>, from: LocalDate? = null, to: LocalDate? = null) {
    println("── $title")
    plan.filter { (from == null || it.date >= from) && (to == null || it.date < to) }.forEach { p ->
        println("  ${p.date.monthValue}/${p.date.dayOfMonth}(${korDow(p.date.dayOfWeek)}) ${p.type.label.padEnd(5)} ${p.title.padEnd(16)} " +
            "${"%.1f".format(p.km).padStart(5)}km 목표 ${pf(p.targetPace).padEnd(6)} reps=${p.reps} 단계=${p.stage} 보정=${p.paceAdj}" +
            (if (p.optional) " (선택)" else "") + (if (PlanEngine.isAdjusted(p)) " ⚡조정:${PlanEngine.adjReason(p)}" else ""))
    }
}

fun main() {
    UserProfile.data = ProfileData(
        nickname = "지훈", birthYear = 1986, sex = "M", heightCm = 174, weightKg = 80,
        goalDistanceKm = 10.0, goalTimeSec = 2700, runDays = 4, weeklyKm = 35, jointCare = true, onboarded = true,
    )
    println("프로필: 최대심박 ${UserProfile.MAX_HR}, 목표 10K 페이스 ${pf(UserProfile.GOAL_10K_PACE)}, 주 ${UserProfile.RUN_DAYS}회")

    // ═══ 시나리오 A: 계획 → (전부 소화) → 다음 계획 ═══
    val t0 = LocalDate.of(2026, 10, 19)
    val hist = history(t0)
    val upd1 = PlanEngine.replan(hist, emptyList(), t0, null, emptyList(), null)
    println("\n══ A-1. 첫 계획 (오늘 $t0, 최근 6주 기록 ${hist.size}회)")
    upd1.note.facts.forEach { println("  · $it") }
    printPlan("첫 계획 4주", upd1.newPlan)

    val t1 = t0.plusDays(28)
    val ran1 = execute(upd1.newPlan, t1, skipQuality = false, fasterBy = 8)
    val upd2 = PlanEngine.replan(hist + ran1, upd1.newPlan, t1, null, emptyList(), upd1.anchor)
    println("\n══ A-2. 4주 뒤 다시 만든 계획 (계획한 훈련을 모두 소화, 목표보다 8초 빠르게 달림)")
    upd2.note.facts.forEach { println("  · $it") }
    printPlan("갱신된 계획 4주", upd2.newPlan, from = t1)

    // ═══ 시나리오 B: 퀄리티를 모두 건너뛴 경우 ═══
    val ran1b = execute(upd1.newPlan, t1, skipQuality = true, fasterBy = 0)
    val updB = PlanEngine.replan(hist + ran1b, upd1.newPlan, t1, null, emptyList(), upd1.anchor)
    println("\n══ B. 퀄리티 훈련을 모두 건너뛰고 이지런·롱런만 한 경우")
    updB.note.facts.filter { it.contains("퀄리티") || it.contains("점진") || it.contains("목표 페이스") }.forEach { println("  · $it") }
    printPlan("갱신된 계획", updB.newPlan, from = t1, to = t1.plusDays(14))

    println("\n══ 제안(계획 점검 카드): ${PlanEngine.proposals(upd1.newPlan, hist + ran1b, t1)}")

    microTests(upd2.newPlan, hist + ran1)
    chatTests(upd2.newPlan, hist + ran1)
    crossTests(upd2.newPlan, hist + ran1)
    halfTests(hist)
}

fun microTests(plan: List<PlanItem>, runs: List<RunSession>) {
    val q = plan.first { it.type == WorkoutType.INTERVAL && it.date >= LocalDate.of(2026, 11, 24) }.date
    println("\n══ C. 블록 중간 조정 (오늘=${q}, 인터벌 날)")
    val calm = PlanEngine.microAdjust(plan, runs, q, null, emptyList(), emptyList())
    println("  평소 상태 → 변경 ${calm.changes.size}개 (0이어야 정상)")
    val spike = runs + listOf(steady(q.minusDays(1), 15.0, 325, 168), steady(q.minusDays(2), 15.0, 325, 168), steady(q.minusDays(3), 15.0, 325, 168))
    val fat = PlanEngine.fatigueNow(spike.filter { it.date <= q }, q, emptyList(), null, emptyList())
    println("  3일 연속 15km 후 피로도 ${fat.score}(${fat.level})")
    val r1 = PlanEngine.microAdjust(plan, spike, q, null, emptyList(), emptyList())
    r1.changes.forEach { println("  변경: $it") }
    printPlan("조정 후 (이번 주)", r1.plan, from = q, to = q.plusDays(7))
    val again = PlanEngine.microAdjust(r1.plan, spike, q, null, emptyList(), emptyList())
    println("  같은 조건으로 다시 점검 → 변경 ${again.changes.size}개 (0이어야 정상)")
    val adj = r1.plan.first { it.date == q }
    val rev = PlanEngine.revert(adj)
    println("  원래대로: ${adj.title}(${adj.type.label}) → ${rev.title}(${rev.type.label}) ${"%.1f".format(rev.km)}km 목표 ${pf(rev.targetPace)} reps=${rev.reps} 팁 ${rev.tips.size}개 / adj=${rev.adj}")
    val planRev = r1.plan.map { if (it.id == adj.id) rev else it }
    val after = PlanEngine.microAdjust(planRev, spike, q, null, emptyList(), emptyList())
    println("  되돌린 뒤 다시 점검 → 오늘 훈련 재조정 여부: ${after.changes.any { it.contains("인터벌") }} (false여야 정상) / 다른 변경 ${after.changes.size}개")
}

fun chatTests(plan: List<PlanItem>, runs: List<RunSession>) {
    val q = plan.first { it.type == WorkoutType.INTERVAL && it.date >= LocalDate.of(2026, 11, 24) }.date
    val noPlanDay = q.plusDays(1)
    val before = runs.filter { it.date < q }
    println("\n══ D. AI 코치 계획 변경")
    var (np, msg) = PlanEngine.applyChatChange(plan, AiCoach.PlanChange(WorkoutType.EASY, 6.4, "가볍게"), q, before)
    println("  인터벌 날 → 이지런 6.4: $msg"); printPlan("  결과", np, from = q, to = q.plusDays(1))
    val ch = np.first { it.date == q }
    println("    팁: ${ch.tips} / 인터벌 팁 잔재 ${ch.tips.any { it.contains("인터벌") }} / reps=${ch.reps}")
    println("    되돌리기 → ${PlanEngine.revert(ch).title} ${PlanEngine.revert(ch).reps}회 ${pf(PlanEngine.revert(ch).targetPace)}")
    for ((label, type, km) in listOf(Triple("TEST", WorkoutType.TEST, 10.0), Triple("TEMPO", WorkoutType.TEMPO, 8.0), Triple("INTERVAL", WorkoutType.INTERVAL, 8.0), Triple("LONG", WorkoutType.LONG, 14.0))) {
        val (np2, m2) = PlanEngine.applyChatChange(plan, AiCoach.PlanChange(type, km, "테스트"), noPlanDay, before)
        val it = np2.first { x -> x.date == noPlanDay }
        println("  계획 없는 날 → $label: $m2  [${it.title} ${"%.1f".format(it.km)}km 목표 ${pf(it.targetPace)} reps=${it.reps} 팁 ${it.tips.size}개]")
    }
    println("  쉬기(오늘 훈련 있음): " + PlanEngine.applyChatChange(plan, AiCoach.PlanChange(null, 0.0, "피로"), q, before).second)
    println("  쉬기(오늘 훈련 없음): " + PlanEngine.applyChatChange(plan, AiCoach.PlanChange(null, 0.0, "피로"), noPlanDay, before).second)
    println("  오늘 이미 러닝함:    " + PlanEngine.applyChatChange(plan, AiCoach.PlanChange(WorkoutType.EASY, 6.0, "x"), q, before + steady(q, 5.0, 400, 130)).second)
}

fun crossTests(plan: List<PlanItem>, runs: List<RunSession>) {
    val q = LocalDate.of(2026, 11, 24)
    println("\n══ E. 크로스 트레이닝 부하")
    val base = runs.filter { it.date <= q } + execute(plan.filter { it.date >= LocalDate.of(2026, 11, 17) }, q, false, 8)   // 이번 주(11/17~23)는 평소대로 달린 상태
    fun cs(t: ActivityType, d: LocalDate, min: Int, km: Double = 0.0) = ActivitySession(t, inst(d), min * 60L, km)
    val cross = listOf(cs(ActivityType.SOCCER, q.minusDays(1), 90), cs(ActivityType.CYCLING, q.minusDays(2), 60), cs(ActivityType.TENNIS, q.minusDays(3), 60), cs(ActivityType.HIKING, q.minusDays(4), 120))
    cross.forEach { println("  ${it.type.label} ${it.durationMin}분 → 러닝 환산 +${"%.1f".format(PlanEngine.crossKm(it, base))}km") }
    val f0 = PlanEngine.fatigueScore(base, q, emptyList(), emptyList())
    val f1 = PlanEngine.fatigueScore(base, q, emptyList(), cross)
    val last7 = base.filter { it.date >= q.minusDays(6) }.sumOf { it.distanceKm }
    val last28 = base.filter { it.date >= q.minusDays(27) }.sumOf { it.distanceKm }
    val eq7 = cross.sumOf { PlanEngine.crossKm(it, base) }
    println("  (참고) 최근 7일 러닝 ${"%.1f".format(last7)}km, 최근 28일 ${"%.1f".format(last28)}km → 최근 부하 비율 ${"%.2f".format(last7 / (last28 / 4))} → 크로스 포함 ${"%.2f".format((last7 + eq7) / ((last28 + eq7) / 4))}")
    println("  피로도: 크로스 없음 $f0 → 있음 $f1")
    val u = PlanEngine.replan(base, plan, q, null, emptyList(), null, cross = cross)
    u.note.facts.filter { it.contains("크로스") || it.contains("쉬는 날") }.forEach { println("  · $it") }
    val big = listOf(cs(ActivityType.SOCCER, q.minusDays(1), 600))
    println("  600분 축구 입력 → +${"%.1f".format(PlanEngine.crossKm(big[0], base))}km (상한 12km)")
    val walkDay = listOf(WalkSession(inst(q.minusDays(1)), 3000, 4.0, 100))
    val manualWalk = listOf(cs(ActivityType.WALKING, q.minusDays(1), 40, 3.0))
    println("  걷기 이중 계산: 삼성 걷기와 같은 날 수동 걷기 → ${PlanEngine.fatigueScore(base, q, walkDay, manualWalk) - PlanEngine.fatigueScore(base, q, walkDay, emptyList())}점 차이(0이어야 정상)")
    val light = listOf(cs(ActivityType.WEIGHT, q.minusDays(1), 60), cs(ActivityType.SQUAT, q.minusDays(2), 10), cs(ActivityType.YOGA, q.minusDays(3), 40))
    println("  가벼운 크로스(웨이트60+스쿼트10+요가40): 피로도 $f0 → ${PlanEngine.fatigueScore(base, q, emptyList(), light)} (변화 없어야 정상)")
}

fun longRepeatRun(d: LocalDate, n: Int, km: Double, pace: Int, hr: Int): RunSession {
    val segs = mutableListOf<RunSegment>()
    segs += RunSegment(SegRole.WARMUP, SegKind.JOG, 0, 720, 1.7, 120, 165)
    var t = 720L
    repeat(n) { i ->
        val dur = (km * pace).toLong()
        segs += RunSegment(SegRole.MAIN, SegKind.RUN, t, dur, km, hr, 176); t += dur
        if (i < n - 1) { segs += RunSegment(SegRole.RECOVERY, SegKind.JOG, t, 120, 0.3, 135, 160); t += 120 }
    }
    segs += RunSegment(SegRole.COOLDOWN, SegKind.JOG, t, 600, 1.4, 125, 160)
    val dist = 1.7 + n * km + 0.3 * (n - 1) + 1.4
    val dur = t + 600
    return RunSession(inst(d), dur, dist, 150, hr + 8, 172, segs.size, null, splits = List(dist.toInt()) { (dur / dist).toInt() }, mainKm = dist, mainSec = dur, segments = segs)
}

fun halfTests(hist: List<RunSession>) {
    val saved = UserProfile.data
    UserProfile.data = saved.copy(goalDistanceKm = 21.1, goalTimeSec = 6600)
    println("\n══ G. 하프 목표(1:50) — 인터벌 칸이 긴 반복으로 바뀌는지")
    val t0 = LocalDate.of(2026, 10, 19)
    val u1 = PlanEngine.replan(hist, emptyList(), t0, null, emptyList(), null)
    u1.note.facts.filter { it.contains("점진") || it.contains("목표 페이스") || it.contains("템포 상한") }.forEach { println("  · $it") }
    printPlan("하프 계획", u1.newPlan, to = t0.plusDays(14))
    val t1 = t0.plusDays(28)
    val ran = u1.newPlan.filter { it.date < t1 && !it.optional }.flatMap { p ->
        val tp = PlanEngine.targetPace(p)
        when (p.type) {
            WorkoutType.INTERVAL -> {
                val m = Regex("(\\d+(?:\\.\\d+)?)km×(\\d+)").find(p.title)
                if (m != null) listOf(longRepeatRun(p.date, m.groupValues[2].toInt(), m.groupValues[1].toDouble(), tp - 4, 168))
                else listOf(intervalRun(p.date, PlanEngine.repsOf(p), tp - 4, 168))
            }
            WorkoutType.TEMPO -> listOf(tempoRun(p.date, 20, tp - 4, 150))
            WorkoutType.TEST -> listOf(steady(p.date, 10.0, tp, 170))
            else -> listOf(steady(p.date, p.km, if (p.targetPace > 0) p.targetPace + 5 else 420, 135))
        }
    }
    val u2 = PlanEngine.replan(hist + ran, u1.newPlan, t1, null, emptyList(), u1.anchor)
    u2.note.facts.filter { it.contains("퀄리티") || it.contains("점진") || it.contains("목표 페이스") }.forEach { println("  · $it") }
    printPlan("하프 갱신 계획", u2.newPlan, from = t1, to = t1.plusDays(14))
    UserProfile.data = saved
}
