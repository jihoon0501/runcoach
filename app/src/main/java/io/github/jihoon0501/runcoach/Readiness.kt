package io.github.jihoon0501.runcoach

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 컨디션 점수를 이루는 신호 하나 */
class ReadinessPart(
    val name: String,       // 수면 / 에너지 / 야간 심박 / 피부 온도 / 러닝 부하
    val value: String,      // 화면에 보여줄 값 (예: 5.2시간 (평소 6.1시간))
    val penalty: Int,       // 감점 (0이면 평소 범위)
    val reason: String,     // 감점 이유 한 문장 (감점이 없으면 빈 문자열)
)

class Readiness(
    val date: LocalDate,            // 어느 날짜의 건강 데이터로 계산했는지
    val score: Int,                 // 0~100
    val level: String,              // 좋음 / 보통 / 낮음
    val headline: String,
    val suggestion: String,         // 오늘 훈련에 대한 제안
    val parts: List<ReadinessPart>,
    val used: Int,                  // 쓴 건강 신호 수 (최대 4)
)

/**
 * 건강 신호(수면, 에너지 점수, 야간 심박, 피부 온도)를 '내 최근 2주 기준'과 비교하고
 * 러닝 피로도를 더해서 오늘 컨디션(0~100)을 만든다. 의료 판단이 아니라 훈련 강도를 정하는 참고용이다.
 */
object ReadinessEngine {

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    private fun hours(min: Int): String = "%.1f시간".format(min / 60.0)

    fun compute(days: List<DayHealth>, runFatigue: Int, plan: PlanItem?, today: LocalDate, walkInfo: String? = null): Readiness? {
        // 오늘 값이 아직 없을 수 있어서 어제까지 중 가장 최근 날짜를 쓴다
        val cur = days.filter { !it.date.isAfter(today) && !it.date.isBefore(today.minusDays(1)) }
            .maxByOrNull { it.date } ?: return null
        val base = days.filter { it.date < cur.date && !it.date.isBefore(cur.date.minusDays(14)) }

        val parts = mutableListOf<ReadinessPart>()
        var used = 0

        // 수면: 평소(중앙값)보다 짧은 정도 + 절대적으로 짧은지
        cur.sleepMin?.let { m ->
            used++
            val med = base.mapNotNull { it.sleepMin?.toDouble() }.takeIf { it.size >= 4 }?.let { median(it) }
            val rel = if (med == null) 0 else when {
                m - med <= -120 -> 18
                m - med <= -60 -> 10
                m - med <= -30 -> 4
                else -> 0
            }
            val abs = when {
                m < 240 -> 12
                m < 300 -> 6
                else -> 0
            }
            val pen = max(rel, abs)
            val value = hours(m) + (if (med != null) " (평소 ${hours(med.roundToInt())})" else "")
            val reason = when {
                pen == 0 -> ""
                med != null && rel >= abs -> "수면이 평소보다 ${(med - m).roundToInt()}분 짧아요"
                else -> "수면이 ${hours(m)}로 짧아요"
            }
            parts += ReadinessPart("수면", value, pen, reason)
        }

        // 에너지 점수: 내 평균 대비 얼마나 낮은지
        cur.energy?.let { e ->
            used++
            val vals = base.mapNotNull { it.energy?.toDouble() }
            val pen = if (vals.size >= 5) {
                val mean = vals.average()
                val sd = max(sqrt(vals.sumOf { (it - mean) * (it - mean) } / vals.size), 5.0)
                val z = (e - mean) / sd
                val relPen = if (z < 0) (-z * 8).toInt().coerceAtMost(25) else 0
                max(relPen, if (e < 50) 12 else 0)
            } else {
                when {
                    e < 50 -> 15
                    e < 60 -> 8
                    e < 70 -> 3
                    else -> 0
                }
            }
            val value = "${e}점" + (if (vals.size >= 5) " (평소 ${vals.average().roundToInt()}점)" else "")
            parts += ReadinessPart("에너지", value, pen, if (pen > 0) "에너지 점수가 평소보다 낮아요(${e}점)" else "")
        }

        // 야간 심박: 평소보다 높으면 피로·몸살 신호
        cur.nightHr?.let { h ->
            used++
            val med = base.mapNotNull { it.nightHr?.toDouble() }.takeIf { it.size >= 4 }?.let { median(it) }
            val dev = if (med == null) 0.0 else h - med
            val pen = when {
                dev >= 8 -> 18
                dev >= 5 -> 10
                dev >= 3 -> 4
                else -> 0
            }
            val value = "${h}bpm" + (if (med != null) " (평소 ${med.roundToInt()}bpm)" else "")
            parts += ReadinessPart("야간 심박", value, pen, if (pen > 0) "자는 동안 심박이 평소보다 ${dev.roundToInt()}bpm 높아요" else "")
        }

        // 피부 온도: 평소보다 높으면 소폭 감점
        cur.skinTemp?.let { t ->
            used++
            val vals = base.mapNotNull { it.skinTemp?.toDouble() }
            val mean = if (vals.size >= 5) vals.average() else null
            val dev = if (mean == null) 0.0 else t - mean
            val pen = when {
                dev >= 1.0 -> 8
                dev >= 0.5 -> 4
                else -> 0
            }
            val value = "%.1f°C".format(t) + (if (mean != null) " (평소 %.1f°C)".format(mean) else "")
            parts += ReadinessPart("피부 온도", value, pen, if (pen > 0) "수면 중 피부 온도가 평소보다 %.1f°C 높아요".format(dev) else "")
        }

        // 러닝 부하: 최근 7일 거리, 강한 러닝, 장거리가 반영된 피로도
        val loadPen = (runFatigue * 0.25).roundToInt()
        parts += ReadinessPart(
            "러닝 부하", "피로도 $runFatigue/100", loadPen,
            if (loadPen >= 8) "최근 러닝 부하가 높아요" else "",
        )

        // 걷기는 러닝 부하에 환산해서 이미 반영돼 있고, 여기서는 참고용으로만 보여준다 (감점 없음)
        if (walkInfo != null) parts += ReadinessPart("걷기(7일)", walkInfo, 0, "")

        val score = (100 - parts.sumOf { it.penalty }).coerceIn(0, 100)
        val level = when {
            score >= 75 -> "좋음"
            score >= 55 -> "보통"
            else -> "낮음"
        }
        val top = parts.filter { it.penalty >= 4 }.sortedByDescending { it.penalty }.take(2).map { it.reason }
        val headline = if (top.isEmpty()) "수면·심박·에너지가 평소 범위예요" else top.joinToString(" · ")
        return Readiness(cur.date, score, level, headline, suggestion(level, plan), parts, used)
    }

    private fun suggestion(level: String, plan: PlanItem?): String {
        val quality = plan != null && plan.type != WorkoutType.EASY
        return when {
            plan == null -> when (level) {
                "좋음" -> "오늘은 예정 훈련이 없어요. 가볍게 몸을 풀어도 좋아요"
                "보통" -> "예정 훈련이 없는 날이에요. 회복에 쓰기 좋아요"
                else -> "예정 훈련이 없어요. 푹 쉬세요"
            }
            level == "좋음" -> "예정대로 진행하세요: ${plan.title}"
            level == "보통" && quality -> {
                val easyKm = max(4, ceil(plan.km * 0.7).toInt())
                "강도를 낮추세요: ${plan.title} 대신 이지런 ${easyKm}km로 바꾸거나 반복 수를 줄여요"
            }
            level == "보통" -> "예정대로 하되 페이스는 평소보다 10~15초 느리게 달리세요: ${plan.title}"
            quality -> "오늘은 ${plan.title}을 쉬거나 20~30분 걷기·아주 가벼운 조깅으로 대체하세요"
            else -> "${plan.title} 거리를 절반으로 줄이거나 쉬세요"
        }
    }
}

// ───────────────────────── 화면 ─────────────────────────

private fun readinessColor(level: String): Color = when (level) {
    "좋음" -> RunColors.Lime
    "보통" -> RunColors.Orange
    else -> RunColors.Pink
}

/** 홈의 슬림 카드: 점수 + 오늘 훈련 제안 한 줄 (누르면 상세) */
@Composable
fun ReadinessCard(r: Readiness, onClick: () -> Unit) {
    val c = readinessColor(r.level)
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(c.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("${r.score}", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = c)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("오늘 컨디션 · ${r.level}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(r.suggestion, fontSize = 10.sp, color = RunColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("›", fontSize = 15.sp, color = RunColors.TextDim)
        }
    }
}

@Composable
fun ReadinessDialog(r: Readiness, onDismiss: () -> Unit) {
    val c = readinessColor(r.level)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RunColors.Card,
        titleContentColor = Color.White,
        textContentColor = RunColors.TextDim,
        title = { Text("오늘 컨디션", fontSize = 17.sp) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${r.score}", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = c)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(r.level, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            "${r.date.monthValue}/${r.date.dayOfMonth} 건강 신호 ${r.used}/4개 + 러닝 부하",
                            fontSize = 10.sp, color = RunColors.TextDim,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(r.headline, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("➡ ${r.suggestion}", fontSize = 11.sp, color = c, lineHeight = 16.sp)

                Spacer(Modifier.height(12.dp))
                Text("무엇을 봤나요?", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(4.dp))
                r.parts.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, fontSize = 11.sp, color = Color.White, modifier = Modifier.width(72.dp))
                        Text(p.value, fontSize = 11.sp, color = RunColors.TextDim, modifier = Modifier.weight(1f))
                        Text(
                            if (p.penalty > 0) "-${p.penalty}" else "✓",
                            fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            color = if (p.penalty > 0) RunColors.Pink else RunColors.Lime,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "내 최근 2주 평균과 비교한 값이에요. 일반적인 훈련 강도 가이드이고 의료 조언이 아니에요. " +
                        "가슴 통증, 어지러움, 비정상적인 심박 같은 증상이 있으면 훈련보다 전문가 상담이 먼저예요.",
                    fontSize = 10.sp, color = RunColors.TextDim, lineHeight = 14.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("확인", color = RunColors.Lime) } },
    )
}
