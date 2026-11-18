// 시뮬레이션 전용: RunColors·Readiness·AiCoach.PlanChange의 최소 정의예요. 실제 정의는 Theme.kt, Readiness.kt, AiCoach.kt에 있어요.
package io.github.jihoon0501.runcoach
import androidx.compose.ui.graphics.Color
object RunColors {
    val Bg = Color(0xFF0A0E1A)
    val BgTop = Color(0xFF1B1446)
    val Card = Color(0xFF151B2E)
    val Lime = Color(0xFFC8FF3E)
    val Violet = Color(0xFF8B5CF6)
    val Pink = Color(0xFFFF5C8A)
    val Orange = Color(0xFFFF9F43)
    val Blue = Color(0xFF4DA6FF)
    val Green = Color(0xFF34D399)
    val TextDim = Color(0xFF9AA4BF)
}
class ReadinessPart(val name: String, val value: String, val penalty: Int, val reason: String)
class Readiness(
    val date: java.time.LocalDate, val score: Int, val level: String, val headline: String,
    val suggestion: String, val parts: List<ReadinessPart>, val used: Int,
) {
    val healthPenalty: Int get() = FatigueMath.healthPenalty(parts.map { it.name to it.penalty })
}
object AiCoach { data class PlanChange(val type: WorkoutType?, val km: Double, val reason: String) }
