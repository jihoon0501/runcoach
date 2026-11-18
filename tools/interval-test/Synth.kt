import kotlin.math.abs

/** (지속 시간 초, 속도 m/s) 단계들로 속도 기록을 만든다. 단계 사이는 6초에 걸쳐 부드럽게 바뀌고 작은 잡음이 섞인다 */
class Phase(val sec: Int, val v: Double)

fun synth(phases: List<Phase>, stepSec: Int, noise: Double = 0.10): List<Pair<Long, Double>> {
    val total = phases.sumOf { it.sec }
    val bounds = ArrayList<Int>()
    var acc = 0
    for (p in phases) { bounds += acc; acc += p.sec }
    fun speedAt(t: Int): Double {
        var i = bounds.indexOfLast { it <= t }
        if (i < 0) i = 0
        val base = phases[i].v
        val into = t - bounds[i]
        return if (i > 0 && into < 6) {
            val prev = phases[i - 1].v
            prev + (base - prev) * (into / 6.0)
        } else base
    }
    val out = ArrayList<Pair<Long, Double>>()
    var t = 0
    var seed = 12345L
    while (t <= total) {
        seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
        val r = ((seed % 2000L) / 1000.0 - 1.0) * noise
        out += (t * 1000L) to (speedAt(minOf(t, total - 1)) + r).coerceAtLeast(0.0)
        t += stepSec
    }
    return out
}

/** 사용자의 실제 인터벌(삼성 헬스 화면): 준비 10분, 4분35초 × 5회(회복 2분), 마무리 10분 */
fun userSession(): List<Phase> = listOf(
    Phase(39, 1.05), Phase(113, 2.46), Phase(77, 2.2), Phase(371, 2.56),       // 준비운동 10:00 (1.44km)
    Phase(275, 3.85), Phase(15, 2.8), Phase(105, 2.2),
    Phase(275, 3.78), Phase(15, 2.4), Phase(105, 1.85),
    Phase(275, 3.78), Phase(15, 2.2), Phase(105, 1.6),
    Phase(275, 3.74), Phase(15, 2.2), Phase(105, 1.68),
    Phase(275, 3.74), Phase(15, 2.2), Phase(105, 1.68),
    Phase(60, 2.3), Phase(541, 1.76),                                           // 마무리 10:01
)

fun hrSeries(speed: List<Pair<Long, Double>>): List<Pair<Long, Double>> =
    speed.filterIndexed { i, _ -> i % 5 == 0 }.map { (t, v) -> t to (95 + v * 17) }
