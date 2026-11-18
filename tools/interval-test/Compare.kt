import io.github.jihoon0501.runcoach.*
import io.github.jihoon0501.runcoach.old.OldRunAnalyzer

var fails = 0
fun check(name: String, cond: Boolean, extra: String = "") {
    println((if (cond) "OK   " else "FAIL ") + name + (if (extra.isNotEmpty()) "  [$extra]" else ""))
    if (!cond) fails++
}
fun segV(s: RunSegment) = if (s.durSec > 0) s.distanceKm * 1000 / s.durSec else 0.0
fun reps(r: RunAnalyzer.Result, minSec: Long = 40, minV: Double = 3.0) =
    r.segments.count { it.role == SegRole.MAIN && it.kind == SegKind.RUN && it.durSec >= minSec && segV(it) >= minV }
fun repsOld(r: OldRunAnalyzer.Result, minSec: Long = 40, minV: Double = 3.0) =
    r.segments.count { it.role == SegRole.MAIN && it.kind == SegKind.RUN && it.durSec >= minSec && segV(it) >= minV }
fun pace(v: Double): String { val s = (1000.0 / v).toInt(); return "${s / 60}'${"%02d".format(s % 60)}\"" }
fun same(a: RunAnalyzer.Result, b: OldRunAnalyzer.Result): Boolean =
    a.segments.size == b.segments.size && a.segments.indices.all {
        a.segments[it].kind == b.segments[it].kind && a.segments[it].role == b.segments[it].role && a.segments[it].durSec == b.segments[it].durSec
    } && a.splits == b.splits

fun main() {
    // 1) 사용자의 실제 인터벌 (1초 / 5초 간격 기록)
    for (step in listOf(1, 5)) {
        val sp = synth(userSession(), step); val hr = hrSeries(sp)
        val n = RunAnalyzer.analyze(sp, hr); val o = OldRunAnalyzer.analyze(sp, hr)
        check("사용자 인터벌(${step}초 간격): 이전 방식은 ${repsOld(o, 150, 3.2)}회, 새 방식은 ${reps(n, 150, 3.2)}회 (실제 5회)", reps(n, 150, 3.2) == 5)
        if (step == 1) {
            println("   새 방식 구간:")
            n.segments.forEach { println("     %-8s %-5s %5.2fkm %4d초 %s".format(it.role, it.kind, it.distanceKm, it.durSec, pace(segV(it).coerceAtLeast(0.1)))) }
            val firstWarm = n.segments.filter { it.role == SegRole.WARMUP }.sumOf { it.durSec }
            check("준비운동이 약 10분으로 분리됨", firstWarm in 540..660, "준비운동 ${firstWarm}초")
            val cool = n.segments.filter { it.role == SegRole.COOLDOWN }.sumOf { it.durSec }
            check("마무리(마지막 회복 약 100초 포함)가 약 12분으로 분리됨", cool in 640..760, "마무리 ${cool}초")
            val mainRuns = n.segments.filter { it.role == SegRole.MAIN && it.kind == SegKind.RUN }
            check("각 반복이 4분35초(275초)에 가까움 (오르내림 포함 255~310초)", mainRuns.all { it.durSec in 255..310 }, mainRuns.map { it.durSec }.toString())
            check("본 운동이 반복 5개 + 사이 회복 4번 (약 6.1km), 1km 구간 6개", n.splits.size == 6 && n.mainKm in 5.9..6.4, "splits=${n.splits} main=${"%.2f".format(n.mainKm)}")
        }
    }
    // 2) 첫 반복이 일부러 느린 경우 (4'55" → 나머지 4'35")
    val slowFirst = userSession().toMutableList().also { it[4] = Phase(275, 3.40) }
    run {
        val sp = synth(slowFirst, 1); val n = RunAnalyzer.analyze(sp, hrSeries(sp))
        check("첫 반복이 조금 느려도(4'55\") 5회로 센다", reps(n, 150, 3.0) == 5, "${reps(n, 150, 3.0)}회")
    }
    // 3) 짧은 반복: 8 × (100초 빠르게 + 90초 조깅)
    val short = mutableListOf(Phase(600, 2.5))
    repeat(8) { short += Phase(100, 4.2); short += Phase(90, 2.0) }
    short += Phase(480, 2.2)
    run {
        val sp = synth(short, 1); val n = RunAnalyzer.analyze(sp, hrSeries(sp)); val o = OldRunAnalyzer.analyze(sp, hrSeries(sp))
        check("짧은 반복 8회: 이전 ${repsOld(o, 60, 3.5)}회 → 새 ${reps(n, 60, 3.5)}회", reps(n, 60, 3.5) == 8)
    }
    // 4) 인터벌이 아닌 운동은 예전과 똑같이 분석돼야 한다
    val easy = listOf(Phase(1200, 2.8), Phase(60, 1.2), Phase(1100, 2.75), Phase(70, 1.1), Phase(900, 2.8))
    val prog = (0 until 40).map { Phase(60, 2.6 + it * 0.025) }
    val tempo = listOf(Phase(600, 2.5), Phase(1200, 3.5), Phase(300, 2.0))
    val longrun = listOf(Phase(1800, 2.9), Phase(120, 1.3), Phase(1800, 2.85), Phase(120, 1.2), Phase(1500, 2.8))
    for ((name, ph) in listOf("꾸준한 이지런" to easy, "점점 빨라지는 런" to prog, "템포런(빠른 구간 1번)" to tempo, "걷기 쉼이 있는 장거리" to longrun)) {
        val sp = synth(ph, 1); val hr = hrSeries(sp)
        val n = RunAnalyzer.analyze(sp, hr); val o = OldRunAnalyzer.analyze(sp, hr)
        check("$name: 예전과 동일한 결과", same(n, o), "구간 ${n.segments.size}개")
    }
    // 5) 준비운동이 꽤 빠른 사람 (5'40"), 반복 3.8 m/s
    val fastWarm = userSession().toMutableList().also { it[1] = Phase(113, 2.95); it[3] = Phase(371, 2.95) }
    run {
        val sp = synth(fastWarm, 1); val n = RunAnalyzer.analyze(sp, hrSeries(sp)); val o = OldRunAnalyzer.analyze(sp, hrSeries(sp))
        println("   (참고) 준비운동 5'40\" 사례: 이전 ${repsOld(o, 150, 3.2)}회 → 새 ${reps(n, 150, 3.2)}회")
    }
    // 6) 기록이 너무 짧거나 느리면 빈 결과
    check("속도 기록이 30개 미만이면 분석하지 않음", RunAnalyzer.analyze(List(10) { (it * 1000L) to 3.0 }, emptyList()).segments.isEmpty())
    println(if (fails == 0) "\n전체 통과" else "\n실패 $fails 개")
}
