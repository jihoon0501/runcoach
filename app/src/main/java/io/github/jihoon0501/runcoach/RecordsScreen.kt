package io.github.jihoon0501.runcoach

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth

// ───────────────────────── 데이터 묶기 (월 → 주차 → 날짜) ─────────────────────────

private data class DayAgg(
    val date: LocalDate,
    val km: Double,
    val pace: Double,
    val hr: Double?,
    val cad: Double?,
)

private class WeekGroup(
    val ym: YearMonth,
    val idx: Int,                 // 그 달의 N주차 (월요일 시작, 1일이 포함된 주가 1주차)
    val days: List<LocalDate>,    // 이 주에서 해당 월에 속하는 날짜들
    val runs: List<RunSession>,
)

private fun groupWeeks(runs: List<RunSession>): List<WeekGroup> =
    runs.groupBy { r ->
        val d = r.date
        val ym = YearMonth.from(d)
        val first = ym.atDay(1)
        val idx = ((d.dayOfMonth - 1 + (first.dayOfWeek.value - 1)) / 7) + 1
        ym to idx
    }.map { (key, list) ->
        val (ym, idx) = key
        val any = list.first().date
        val monday = any.minusDays((any.dayOfWeek.value - 1).toLong())
        val days = (0..6).map { monday.plusDays(it.toLong()) }.filter { YearMonth.from(it) == ym }
        WeekGroup(ym, idx, days, list)
    }.sortedWith(compareByDescending<WeekGroup> { it.ym }.thenByDescending { it.idx })

private fun aggregate(date: LocalDate, runs: List<RunSession>): DayAgg? {
    val rs = runs.filter { it.date == date }
    if (rs.isEmpty()) return null
    val w = rs.map { it.distanceKm }
    fun wavg(vs: List<Double?>): Double? {
        var num = 0.0
        var den = 0.0
        vs.forEachIndexed { i, v ->
            if (v != null) {
                num += v * w[i]
                den += w[i]
            }
        }
        return if (den > 0) num / den else null
    }
    return DayAgg(
        date = date,
        km = rs.sumOf { it.distanceKm },
        pace = wavg(rs.map { r -> r.paceSecPerKm.takeIf { it > 0 } }) ?: 0.0,
        hr = wavg(rs.map { it.avgHr?.toDouble() }),
        cad = wavg(rs.map { it.cadence?.toDouble() }),
    )
}

private class Series(
    val key: String,
    val label: String,
    val color: Color,
    val values: List<Double?>,
    val higherIsUp: Boolean,
    val fmt: (Double) -> String,
)

private fun shortPace(v: Double): String {
    val t = Math.round(v).toInt()
    return "${t / 60}'${"%02d".format(t % 60)}"
}

// ───────────────────────── 주간 콤보 차트 ─────────────────────────

@Composable
private fun WeekComboChart(g: WeekGroup) {
    val aggs = remember(g) { g.days.map { aggregate(it, g.runs) } }
    val series = remember(g) {
        listOf(
            Series("pace", "페이스", RunColors.Blue, aggs.map { a -> a?.pace?.takeIf { it > 0 } }, false) { shortPace(it) },
            Series("hr", "심박", RunColors.Pink, aggs.map { it?.hr }, true) { "${Math.round(it)}" },
            Series("cad", "케이던스", RunColors.Violet, aggs.map { it?.cad }, true) { "${Math.round(it)}" },
        )
    }
    var visible by remember { mutableStateOf(setOf("pace", "hr", "cad")) }
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    val maxKm = (aggs.mapNotNull { it?.km }.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
    val dotBg = Color(0xFF151B2E)

    Column(Modifier.fillMaxWidth()) {
        Text(
            "막대 = 거리(km) · 선 = 페이스·심박·케이던스 (이 주 안에서의 상대 위치)",
            fontSize = 9.sp, color = RunColors.TextDim,
        )
        Spacer(Modifier.height(4.dp))

        Box(Modifier.fillMaxWidth().height(104.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val n = g.days.size
                val slotW = size.width / n
                val topPad = 6.dp.toPx()
                val botPad = 2.dp.toPx()
                val h = size.height - topPad - botPad

                // 가이드 라인
                for (k in 0..2) {
                    val y = topPad + h * k / 2f
                    drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                // 선택한 날짜 강조
                g.days.forEachIndexed { i, d ->
                    if (d == selected) {
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.07f),
                            topLeft = Offset(i * slotW + 1f, 0f),
                            size = Size(slotW - 2f, size.height),
                            cornerRadius = CornerRadius(6.dp.toPx()),
                        )
                    }
                }
                // 거리 막대
                aggs.forEachIndexed { i, a ->
                    if (a != null) {
                        val bh = (a.km / maxKm).toFloat() * h * 0.9f
                        val bw = slotW * 0.5f
                        drawRoundRect(
                            color = RunColors.Lime.copy(alpha = if (g.days[i] == selected) 0.55f else 0.32f),
                            topLeft = Offset(i * slotW + (slotW - bw) / 2f, topPad + h - bh),
                            size = Size(bw, bh),
                            cornerRadius = CornerRadius(4.dp.toPx()),
                        )
                    }
                }
                // 이어지는 선 (달린 날끼리 연결)
                series.filter { it.key in visible }.forEach { s ->
                    val present = s.values.filterNotNull()
                    if (present.isEmpty()) return@forEach
                    val mn = present.min()
                    val mx = present.max()
                    val pts = mutableListOf<Offset>()
                    s.values.forEachIndexed { i, v ->
                        if (v != null) {
                            val t = if (mx - mn < 1e-6) 0.5 else (v - mn) / (mx - mn)
                            val up = if (s.higherIsUp) t else 1.0 - t
                            val y = topPad + h * 0.08f + h * 0.84f * (1f - up.toFloat())
                            pts += Offset(i * slotW + slotW / 2f, y)
                        }
                    }
                    if (pts.size >= 2) {
                        val path = Path()
                        pts.forEachIndexed { k, p -> if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                        drawPath(
                            path = path,
                            color = s.color,
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                    pts.forEach { p ->
                        drawCircle(dotBg, radius = 4.5.dp.toPx(), center = p)
                        drawCircle(s.color, radius = 3.dp.toPx(), center = p)
                    }
                }
            }
            // 날짜 탭 영역
            Row(Modifier.fillMaxSize()) {
                g.days.forEach { d ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) { selected = if (selected == d) null else d }
                    )
                }
            }
        }

        // 날짜 라벨: 요일 / 일 / 거리
        Row(Modifier.fillMaxWidth()) {
            g.days.forEachIndexed { i, d ->
                val a = aggs[i]
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        korDow(d.dayOfWeek), fontSize = 9.sp,
                        color = if (d == selected) RunColors.Lime else RunColors.TextDim,
                    )
                    Text(
                        "${d.dayOfMonth}", fontSize = 10.sp,
                        fontWeight = if (a != null) FontWeight.Bold else FontWeight.Normal,
                        color = if (a != null) Color.White else RunColors.TextDim,
                    )
                    Text(
                        if (a != null) "%.1f".format(a.km) else "-", fontSize = 9.sp,
                        color = if (a != null) RunColors.Lime else RunColors.TextDim,
                    )
                }
            }
        }

        // 선택한 날짜 상세
        val sel = selected
        val selAgg = if (sel != null) aggs.getOrNull(g.days.indexOf(sel)) else null
        if (sel != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (selAgg != null) {
                    "${sel.monthValue}/${sel.dayOfMonth}(${korDow(sel.dayOfWeek)}) · %.1fkm · %s · ♥%s · %sspm".format(
                        selAgg.km,
                        formatPace(selAgg.pace),
                        selAgg.hr?.let { Math.round(it).toString() } ?: "-",
                        selAgg.cad?.let { Math.round(it).toString() } ?: "-",
                    )
                } else {
                    "${sel.monthValue}/${sel.dayOfMonth}(${korDow(sel.dayOfWeek)}) · 러닝 기록이 없어요"
                },
                fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            )
        }

        // 선 범례 (눌러서 켜고 끄기)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            series.forEach { s ->
                val on = s.key in visible
                val present = s.values.filterNotNull()
                val range = when {
                    present.isEmpty() -> "-"
                    present.size == 1 -> s.fmt(present[0])
                    else -> "${s.fmt(present.min())}~${s.fmt(present.max())}"
                }
                Row(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(s.color.copy(alpha = if (on) 0.18f else 0.05f))
                        .clickable { visible = if (on) visible - s.key else visible + s.key }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(s.color.copy(alpha = if (on) 1f else 0.35f)))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${s.label} $range", fontSize = 9.sp, maxLines = 1,
                        color = if (on) Color.White else RunColors.TextDim,
                    )
                }
            }
        }
    }
}

// ───────────────────────── 월 페이지 ─────────────────────────

@Composable
private fun MonthSummary(ym: YearMonth, runs: List<RunSession>) {
    val km = runs.sumOf { it.distanceKm }
    val avgPace = if (km > 0) runs.sumOf { it.paceSecPerKm * it.distanceKm } / km else 0.0
    val hrRuns = runs.filter { it.avgHr != null }
    val hrKm = hrRuns.sumOf { it.distanceKm }
    val avgHr = if (hrKm > 0) Math.round(hrRuns.sumOf { (it.avgHr ?: 0) * it.distanceKm } / hrKm).toInt() else null
    val cadRuns = runs.filter { it.cadence != null }
    val cadKm = cadRuns.sumOf { it.distanceKm }
    val avgCad = if (cadKm > 0) Math.round(cadRuns.sumOf { (it.cadence ?: 0) * it.distanceKm } / cadKm).toInt() else null
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${ym.monthValue}월 요약", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f))
            Text("${runs.size}회", fontSize = 10.sp, color = RunColors.TextDim)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("거리", "%.0fkm".format(km))
            Stat("평균 페이스", formatPace(avgPace))
            Stat("평균 심박", avgHr?.toString() ?: "-")
            Stat("케이던스", avgCad?.toString() ?: "-")
        }
    }
}

@Composable
private fun WeekHeader(g: WeekGroup) {
    val first = g.days.first()
    val last = g.days.last()
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${g.ym.monthValue}월 ${g.idx}주차", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Spacer(Modifier.width(6.dp))
        Text(
            "${first.monthValue}/${first.dayOfMonth}~${last.monthValue}/${last.dayOfMonth}",
            fontSize = 10.sp, color = RunColors.TextDim, modifier = Modifier.weight(1f),
        )
        Text("%.1fkm · ${g.runs.size}회".format(g.runs.sumOf { it.distanceKm }), fontSize = 10.sp, color = RunColors.TextDim)
    }
}

/** 한 달 분량 페이지: 월 요약 → 주차별(최근 주 먼저) 차트와 러닝 목록 */
@Composable
private fun MonthPage(
    ym: YearMonth,
    allRuns: List<RunSession>,
    aiResults: Map<String, AiResult>,
    aiLoading: List<String>,
    onAiEval: (RunSession) -> Unit,
) {
    val monthRuns = remember(allRuns, ym) { allRuns.filter { YearMonth.from(it.date) == ym } }
    val groups = remember(monthRuns) { groupWeeks(monthRuns) }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (monthRuns.isEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(
                        if (allRuns.isEmpty()) "아직 불러온 기록이 없어요. 홈에서 연결해주세요." else "이 달에는 러닝 기록이 없어요.",
                        color = RunColors.TextDim, fontSize = 12.sp,
                    )
                }
            }
        } else {
            item(key = "summary") { MonthSummary(ym, monthRuns) }
            groups.forEach { g ->
                item(key = "w-${g.idx}") { WeekHeader(g) }
                item(key = "c-${g.idx}") { GlassCard(Modifier.fillMaxWidth()) { WeekComboChart(g) } }
                items(g.runs.sortedByDescending { it.start }, key = { it.start.toEpochMilli() }) { run ->
                    val key = run.start.toEpochMilli().toString()
                    RunRow(run, aiResults[key], key in aiLoading) { onAiEval(run) }
                }
            }
        }
    }
}

/** 가장 오래된 기록의 달부터 이번 달까지 (기록이 없는 달도 포함해서 연속으로) */
private fun monthsToShow(runs: List<RunSession>, today: LocalDate): List<YearMonth> {
    val last = YearMonth.from(today)
    val firstRun = runs.minOfOrNull { YearMonth.from(it.date) }
    val first = if (firstRun != null && firstRun.isBefore(last)) firstRun else last
    val out = mutableListOf<YearMonth>()
    var m = first
    while (!m.isAfter(last)) {
        out += m
        m = m.plusMonths(1)
    }
    return out
}

// ───────────────────────── 기록 탭 ─────────────────────────

@Composable
fun RecordsScreen(
    runs: List<RunSession>,
    today: LocalDate,
    aiResults: Map<String, AiResult>,
    aiLoading: List<String>,
    onAiEval: (RunSession) -> Unit,
) {
    val months = remember(runs, today) { monthsToShow(runs, today) }
    val pagerState = rememberPagerState(initialPage = (months.size - 1).coerceAtLeast(0)) { months.size }
    val scope = rememberCoroutineScope()

    // 기록을 늦게 불러와서 달이 늘어나면, 처음 한 번만 가장 최근 달로 이동
    var jumped by remember { mutableStateOf(false) }
    LaunchedEffect(months.size) {
        if (!jumped && months.isNotEmpty()) {
            pagerState.scrollToPage(months.lastIndex)
            jumped = true
        }
    }

    val current = months.getOrNull(pagerState.currentPage)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp), verticalAlignment = Alignment.Bottom) {
            Text("러닝 기록", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, modifier = Modifier.weight(1f))
            Text("전체 ${runs.size}회 · %.0fkm".format(runs.sumOf { it.distanceKm }), fontSize = 11.sp, color = RunColors.TextDim)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { scope.launch { if (pagerState.currentPage > 0) pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                modifier = Modifier.size(34.dp),
            ) { Text("‹", fontSize = 22.sp, color = if (pagerState.currentPage > 0) RunColors.Lime else RunColors.TextDim) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    current?.let { "${it.year}년 ${it.monthValue}월" } ?: "-",
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White,
                )
                Text("좌우로 밀어서 월 이동", fontSize = 9.sp, color = RunColors.TextDim)
            }
            IconButton(
                onClick = { scope.launch { if (pagerState.currentPage < months.lastIndex) pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                modifier = Modifier.size(34.dp),
            ) { Text("›", fontSize = 22.sp, color = if (pagerState.currentPage < months.lastIndex) RunColors.Lime else RunColors.TextDim) }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { page ->
            MonthPage(months[page], runs, aiResults, aiLoading, onAiEval)
        }
    }
}
