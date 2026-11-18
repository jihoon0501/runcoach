import io.github.jihoon0501.runcoach.PaceGradient
import kotlin.math.abs

var fails = 0
fun check(n: String, c: Boolean, e: String = "") { println((if (c) "OK   " else "FAIL ") + n + (if (e.isNotEmpty()) "  [$e]" else "")); if (!c) fails++ }
fun r(c: Int) = (c shr 16) and 0xFF
fun g(c: Int) = (c shr 8) and 0xFF
fun b(c: Int) = c and 0xFF
fun dist(a: Int, c: Int) = abs(r(a) - r(c)) + abs(g(a) - g(c)) + abs(b(a) - b(c))
fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

fun main() {
    // 대표 속도의 색
    val samples = listOf(0.0 to "멈춤", 1.05 to "걷기 15'54\"", 1.7 to "회복 걷기 9'48\"", 2.2 to "회복 조깅 7'34\"", 2.46 to "준비운동 6'46\"", 3.0 to "이지~꾸준 5'33\"", 3.63 to "목표 4'35\"", 3.85 to "인터벌 4'20\"", 4.4 to "3'47\"", 5.5 to "3'02\"")
    samples.forEach { (v, n) -> println("   %-18s %.2fm/s → %s".format(n, v, hex(PaceGradient.argb(v)))) }
    check("양 끝은 고정: 음수·NaN은 멈춤색, 아주 빠르면 분홍", PaceGradient.argb(-1.0) == PaceGradient.argb(0.0) && PaceGradient.argb(Double.NaN) == PaceGradient.argb(0.0) && PaceGradient.argb(9.0) == PaceGradient.argb(5.2))
    check("알파는 불투명", (0 until 60).all { (PaceGradient.argb(it * 0.1) ushr 24) == 0xFF })
    // 연속성: 속도가 0.02 바뀔 때 색 변화(R+G+B 합)가 작다
    var maxJump = 0
    var v = 0.0
    while (v < 5.4) { maxJump = maxOf(maxJump, dist(PaceGradient.argb(v), PaceGradient.argb(v + 0.02))); v += 0.02 }
    check("연속적: 속도 0.02 차이의 최대 색 변화가 작음", maxJump <= 20, "최대 $maxJump")
    // 서로 다른 페이스는 눈으로 구분될 만큼 다른 색
    check("준비운동(2.46)과 반복(3.85)은 확실히 다른 색", dist(PaceGradient.argb(2.46), PaceGradient.argb(3.85)) > 250)
    check("회복(1.7)과 반복(3.85)은 확실히 다른 색", dist(PaceGradient.argb(1.7), PaceGradient.argb(3.85)) > 250)
    check("이지런 5'57\"(2.8)과 5'12\"(3.2)도 구분됨 (페이스 45초 차이)", dist(PaceGradient.argb(2.8), PaceGradient.argb(3.2)) > 60, "${dist(PaceGradient.argb(2.8), PaceGradient.argb(3.2))}")
    check("목표 4'35\"(3.63)와 4'20\"(3.85)도 구분됨 (15초 차이)", dist(PaceGradient.argb(3.63), PaceGradient.argb(3.85)) >= 25, "${dist(PaceGradient.argb(3.63), PaceGradient.argb(3.85))}")

    // 막대 눈금: 사용자 인터벌 구성 (준비, 반복 5회와 회복, 마무리)
    val durs = listOf(44L, 557L, 281L, 116L, 295L, 94L, 296L, 102L, 294L, 101L, 294L, 702L)
    val spds = listOf(1.05, 2.50, 3.84, 2.24, 3.66, 1.80, 3.65, 1.60, 3.64, 1.68, 3.64, 1.60)
    val stops = PaceGradient.barStops(durs, spds)
    check("눈금 개수는 구간×2", stops.size == durs.size * 2, "${stops.size}")
    check("위치는 0에서 시작해 1에서 끝남", stops.first().first == 0f && stops.last().first == 1f)
    check("위치는 줄어들지 않음", stops.zipWithNext().all { it.first.first <= it.second.first + 1e-6f })
    check("위치는 모두 0~1", stops.all { it.first in 0f..1f })
    // 각 구간 가운데에서 자기 색
    val total = durs.sum().toFloat()
    var acc = 0f
    var allMine = true
    durs.indices.forEach { i ->
        val w = durs[i] / total
        val mid = acc + w / 2
        val s1 = stops[2 * i]; val s2 = stops[2 * i + 1]
        if (!(s1.first <= mid + 1e-6f && mid <= s2.first + 1e-6f) || s1.second != PaceGradient.argb(spds[i])) allMine = false
        acc += w
    }
    check("각 구간의 가운데는 그 구간의 색", allMine)
    // 아주 짧은 구간(1초)이 섞여도 눈금이 깨지지 않음
    val st2 = PaceGradient.barStops(listOf(1L, 1L, 600L, 2L), listOf(1.0, 3.0, 4.0, 2.0))
    check("아주 짧은 구간이 있어도 눈금이 정상", st2.zipWithNext().all { it.first.first <= it.second.first + 1e-6f } && st2.last().first == 1f)
    check("빈 목록·길이 불일치는 빈 결과", PaceGradient.barStops(emptyList(), emptyList()).isEmpty() && PaceGradient.barStops(listOf(5L), emptyList()).isEmpty())
    check("범례 눈금: 8개, 0에서 1까지", PaceGradient.legendStops(8).let { it.size == 8 && it.first().first == 0f && it.last().first == 1f })
    println(if (fails == 0) "\n전체 통과" else "\n실패 $fails 개")
}
