package io.github.jihoon0501.runcoach

/**
 * 피로도와 컨디션의 점수 규칙 (안드로이드에 의존하지 않는 순수 코드라서 시험할 수 있다).
 *
 * 용어 정리
 *  - 러닝 피로도(0~100, 높을수록 피곤): 러닝 기록(+걷기 환산)만으로 계산한 부하. 건강 신호는 보지 않는다.
 *  - 건강 신호 감점: 수면·에너지·야간 심박·피부 온도가 내 최근 2주 기준보다 나쁜 만큼의 감점 합. 러닝 부하는 넣지 않는다.
 *  - 피로도(계획 탭, 높을수록 피곤) = 러닝 피로도 + 건강 신호 가산점. 계획의 휴식일과 강도를 정하는 점수다.
 *  - 오늘 컨디션(홈, 0~100, 높을수록 좋음) = 100 − 건강 신호 감점 − 러닝 부하 감점(러닝 피로도의 25%).
 *
 * 러닝 부하를 한 번만 세는 것이 핵심이다. 예전에는 계획의 가산점을 '컨디션 점수'로 정했는데, 그 점수에는 이미
 * 러닝 부하(피로도의 25%)가 들어 있어서 러닝 부하가 피로도에 두 번 반영됐다. 이제 가산점은 건강 신호 감점만 본다.
 */
object FatigueMath {

    const val LOAD_PART = "러닝 부하"
    const val WALK_PART = "걷기(7일)"

    /** 컨디션 항목들 중 건강 신호의 감점 합. 러닝 부하와 걷기(참고용) 항목은 뺀다 */
    fun healthPenalty(parts: List<Pair<String, Int>>): Int =
        parts.filter { it.first != LOAD_PART && it.first != WALK_PART }.sumOf { it.second }

    /**
     * 건강 신호가 나쁠수록 피로도에 더하는 점수. 기준은 건강 신호 감점 합이다 (20 이상 +5, 35 이상 +12, 50 이상 +20).
     * 예전 기준(컨디션 70/55/40 미만)을 러닝 피로도가 보통(약 40)일 때의 감점으로 옮긴 값이라 평소 계획은 거의 그대로다.
     */
    fun healthAdd(healthPenalty: Int?): Int = when {
        healthPenalty == null -> 0
        healthPenalty >= 50 -> 20
        healthPenalty >= 35 -> 12
        healthPenalty >= 20 -> 5
        else -> 0
    }

    /** 계획 탭의 피로도 = 러닝 피로도 + 건강 신호 가산점 (0~100) */
    fun combine(runningScore: Int, healthPenalty: Int?): Int =
        (runningScore + healthAdd(healthPenalty)).coerceIn(0, 100)

    /** 피로도 단계: 70 이상 높음, 45 이상 보통, 그 미만 낮음 */
    fun level(score: Int): String = when {
        score >= 70 -> "높음"
        score >= 45 -> "보통"
        else -> "낮음"
    }

    /** 컨디션 단계: 75 이상 좋음, 55 이상 보통, 그 미만 낮음 */
    fun readinessLevel(score: Int): String = when {
        score >= 75 -> "좋음"
        score >= 55 -> "보통"
        else -> "낮음"
    }
}
