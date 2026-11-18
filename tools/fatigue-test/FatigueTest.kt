import io.github.jihoon0501.runcoach.FatigueMath
import kotlin.math.roundToInt

var fails = 0
fun check(n: String, c: Boolean, e: String = "") { println((if (c) "OK   " else "FAIL ") + n + (if (e.isNotEmpty()) "  [$e]" else "")); if (!c) fails++ }

/** 예전 규칙: 러닝 부하가 들어 있는 컨디션 점수로 가산점을 정했다 */
fun oldAdd(readinessScore: Int) = when { readinessScore < 40 -> 20; readinessScore < 55 -> 12; readinessScore < 70 -> 5; else -> 0 }
fun readiness(healthPen: Int, running: Int) = (100 - healthPen - (running * 0.25).roundToInt()).coerceIn(0, 100)

fun main() {
    val parts = listOf("수면" to 10, "에너지" to 8, "야간 심박" to 4, "피부 온도" to 0, FatigueMath.LOAD_PART to 14, FatigueMath.WALK_PART to 0)
    check("건강 신호 감점은 러닝 부하와 걷기를 뺀 합", FatigueMath.healthPenalty(parts) == 22, "${FatigueMath.healthPenalty(parts)}")
    check("항목이 없으면 0", FatigueMath.healthPenalty(emptyList()) == 0)

    val edges = listOf(0 to 0, 19 to 0, 20 to 5, 34 to 5, 35 to 12, 49 to 12, 50 to 20, 99 to 20)
    check("가산점 기준: 20→+5, 35→+12, 50→+20", edges.all { FatigueMath.healthAdd(it.first) == it.second })
    check("건강 데이터가 없으면(null) 가산점 없음", FatigueMath.healthAdd(null) == 0)
    check("합쳐도 100을 넘지 않음", FatigueMath.combine(95, 60) == 100 && FatigueMath.combine(0, null) == 0)

    // 핵심: 같은 건강 상태면 러닝 피로도가 얼마든 가산점이 같다 (이중 반영 없음)
    val pen = 22
    val adds = listOf(0, 40, 80).map { FatigueMath.combine(it, pen) - it }
    check("같은 건강 신호면 러닝 피로도 0·40·80에서 가산점이 같음", adds.distinct().size == 1, "$adds")
    // 예전 규칙은 같은 건강 신호인데도 러닝 피로도가 높으면 가산점이 커졌다
    val oldAdds = listOf(0, 40, 80).map { oldAdd(readiness(pen, it)) }
    check("(이전 규칙은 러닝 피로도에 따라 가산점이 달라졌음: 이중 반영)", oldAdds.distinct().size > 1, "이전 $oldAdds → 지금 $adds")
    // 이전 규칙의 평소 구간(러닝 피로도 약 40)과 새 기준이 같은 판정
    // 경계값(20·35·50점)은 '이상'과 '초과'의 차이로 1점 어긋나므로, 그 사이 값들로 비교한다
    val offBoundary = (0..70).filter { it != 20 && it != 35 && it != 50 }
    val agree = offBoundary.count { p -> FatigueMath.healthAdd(p) == oldAdd(readiness(p, 40)) }
    check("러닝 피로도 40일 때의 이전 판정과 경계값 3개를 뺀 모든 건강 감점(0~70)에서 같음", agree == offBoundary.size, "일치 $agree/${offBoundary.size}")

    check("피로도 단계: 44 낮음, 45 보통, 69 보통, 70 높음", listOf(44, 45, 69, 70).map { FatigueMath.level(it) } == listOf("낮음", "보통", "보통", "높음"))
    check("컨디션 단계: 54 낮음, 55 보통, 74 보통, 75 좋음", listOf(54, 55, 74, 75).map { FatigueMath.readinessLevel(it) } == listOf("낮음", "보통", "보통", "좋음"))

    // 두 숫자의 방향이 반대라는 것을 사례로 확인: 어제 강한 인터벌 + 수면 좋음 / 달린 건 적은데 수면 나쁨
    val a = FatigueMath.combine(72, 0)             // 러닝 피로도 72, 건강 신호 좋음
    val aReady = readiness(0, 72)
    check("사례 A(강한 러닝, 잘 잠): 피로도 ${a} ${FatigueMath.level(a)} · 컨디션 ${aReady} ${FatigueMath.readinessLevel(aReady)}", FatigueMath.level(a) == "높음" && FatigueMath.readinessLevel(aReady) != "낮음")
    val b = FatigueMath.combine(10, 40)            // 러닝 피로도 10, 건강 신호 감점 40
    val bReady = readiness(40, 10)
    check("사례 B(적게 달림, 잠 부족): 피로도 ${b} ${FatigueMath.level(b)} · 컨디션 ${bReady} ${FatigueMath.readinessLevel(bReady)}", FatigueMath.level(b) == "낮음" && FatigueMath.readinessLevel(bReady) != "좋음")
    println(if (fails == 0) "\n전체 통과" else "\n실패 $fails 개")
}
