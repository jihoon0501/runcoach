package io.github.jihoon0501.runcoach

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth

/** 신체 탭에서 볼 수 있는 신체 항목 */
private enum class BodyMetric(val label: String, val unit: String, val deltaUnit: String, val color: Color) {
    WEIGHT("체중", "kg", "kg", RunColors.Lime),
    FAT("체지방", "%", "%p", RunColors.Orange),
    MUSCLE("골격근", "kg", "kg", RunColors.Violet),
    BMI("BMI", "", "", RunColors.Blue);

    fun value(b: BodyInfo): Float? = when (this) {
        WEIGHT -> b.weightKg
        FAT -> b.bodyFatPct
        MUSCLE -> b.skeletalMuscleKg
        BMI -> b.bmi
    }

    fun fmt(v: Float): String = "%.1f".format(v) + unit
}

private val RANGE_LABELS = listOf("3개월", "6개월", "1년", "전체")
private val RANGE_WEEKS = listOf(13, 26, 52, 260)

private const val CHART_H = 130f      // 그래프 높이(dp)
private const val CHART_PAD = 8f      // 위아래 여백(dp). 눈금 글자 위치 계산에도 같이 쓴다
private val GRID = Color.White.copy(alpha = 0.08f)
private val KM_COLOR = RunColors.Blue

private class ChartPoint(val day: LocalDate, val v: Float)

private fun fmtDelta(cur: Float?, prev: Float?, m: BodyMetric): String? {
    if (cur == null || prev == null) return null
    val d = cur - prev
    if (Math.abs(d) < 0.05f) return "–"
    return (if (d > 0) "▲" else "▼") + "%.1f".format(Math.abs(d))
}

/**
 * 신체 탭: 체중·체지방·골격근·BMI의 변화 이력을 러닝 거리와 함께 보여 준다.
 * 그래프는 누르거나 좌우로 끌면 그 시점의 값이 보이고, 아래 이력 표에서 같은 날짜가 강조된다.
 * 가져오기와 자동 반영 같은 설정은 맨 아래 카드에 있다.
 */
@Composable
fun BodyScreen(runs: List<RunSession>, today: LocalDate, onProfileSaved: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val store = remember { BodyStore(context.applicationContext, prefs.dataSource) }
    var version by remember { mutableStateOf(0) }
    val list = remember(version) { store.load() }
    var metric by remember { mutableStateOf(BodyMetric.WEIGHT) }
    var rangeIdx by remember { mutableStateOf(1) }
    var selDay by remember { mutableStateOf<LocalDate?>(null) }

    val latest = BodyStore.latest(list)
    val weeksN = RANGE_WEEKS[rangeIdx]
    val since = today.minusWeeks(weeksN.toLong())

    var bodyTab by remember { mutableStateOf(0) }  // 0=그래프 1=달력 2=이력

    Column(
        Modifier.fillMaxSize().background(RunColors.Bg)
    ) {
        // ── 상단 고정 영역 (스크롤 안 됨) ──
        Column(
            Modifier
                .fillMaxWidth()
                .background(RunColors.Bg)
                .padding(horizontal = 14.dp)
                .padding(top = 10.dp, bottom = 6.dp)
        ) {
            // 타이틀
            Text("신체", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Spacer(Modifier.height(10.dp))

            // 지표 선택 버튼 (가로 스크롤)
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BodyMetric.values().forEach { m ->
                    val sel = m == metric
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (sel) RunColors.Violet else Color.White.copy(alpha = 0.08f))
                            .clickable { metric = m; selDay = null }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(m.label, fontSize = 12.sp, color = Color.White,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 최신값 요약 한 줄
            if (latest != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    latest.weightKg?.let { StatChip("체중", "%.1fkg".format(it)) }
                    latest.bodyFatPct?.let { StatChip("체지방", "%.1f%%".format(it)) }
                    latest.skeletalMuscleKg?.let { StatChip("근육", "%.1fkg".format(it)) }
                    latest.bmi?.let { StatChip("BMI", "%.1f".format(it)) }
                }
                Spacer(Modifier.height(4.dp))
            }

            // 탭 바
            androidx.compose.material3.TabRow(
                selectedTabIndex = bodyTab,
                containerColor = Color.Transparent,
                contentColor = Color.White,
                indicator = {},  // 기본 인디케이터 숨김 — Tab selected 색상으로 구분
            ) {
                listOf("📈 그래프", "📅 달력", "📋 이력").forEachIndexed { i, lbl ->
                    androidx.compose.material3.Tab(
                        selected = bodyTab == i,
                        onClick = { bodyTab = i },
                        text = { Text(lbl, fontSize = 11.sp) },
                    )
                }
            }
        }

        // ── 탭별 내용 (스크롤 가능) ──
        when (bodyTab) {
            0 -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        SectionTitle("${metric.label} 변화")
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RANGE_WEEKS.indices.forEach { i ->
                                val sel = i == rangeIdx
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (sel) RunColors.Violet else Color.White.copy(alpha = 0.08f))
                                        .clickable { rangeIdx = i }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text("${RANGE_WEEKS[i]}주", fontSize = 11.sp, color = Color.White,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        val pts = BodyStore.weekly(list, runs, weeksN, today)
                            .flatMap { w -> w.items.mapNotNull { d -> metric.value(d)?.let { v -> ChartPoint(d.date, v) } } }
                        if (pts.size >= 2) LineChartPanel(pts, metric, selDay) { selDay = it }
                        else Text("데이터가 부족해요 (${metric.label} ${weeksN}주)", color = RunColors.TextDim, fontSize = 11.sp)
                    }
                }
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        SectionTitle("러닝과 함께 보기")
                        Spacer(Modifier.height(6.dp))
                        val wks = BodyStore.weekly(list, runs, weeksN, today)
                        if (wks.isNotEmpty()) ComboChartPanel(wks, metric, selDay) { selDay = it }
                        else Text("데이터가 부족해요 (${metric.label} ${weeksN}주)", color = RunColors.TextDim, fontSize = 11.sp)
                    }
                }
                item {
                    BodyInfoCard(onProfileSaved = onProfileSaved, onDataChanged = { version++ })
                }
                item {
                    Text(
                        "가정용 체성분 측정값은 수분 상태와 측정 시각에 따라 하루 단위로 크게 흔들릴 수 있어요. " +
                        "며칠~몇 주 단위의 흐름으로 보세요. 이 정보는 일반적인 참고용이며 의료 조언이 아니에요.",
                        fontSize = 10.sp, color = RunColors.TextDim, lineHeight = 15.sp,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
            1 -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { CalendarCard(list, runs, metric, today, weeksN, selDay) { selDay = it } }
            }
            2 -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { HistoryCard(list, runs, metric, selDay) { selDay = it } }
            }
        }
    }
}
@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(label, fontSize = 9.sp, color = RunColors.TextDim)
    }
}

// ───────────────────────── 그래프 ─────────────────────────

/** 세로축 눈금 글자. 그래프의 가로 눈금선과 같은 높이에 놓는다 */
@Composable
private fun AxisLabels(axis: ChartMath.Axis, color: Color, alignEnd: Boolean) {
    Box(Modifier.width(34.dp).height(CHART_H.dp)) {
        axis.ticks.forEach { t ->
            val y = CHART_PAD + (1f - axis.frac(t)) * (CHART_H - 2 * CHART_PAD)
            Text(
                axis.label(t),
                fontSize = 9.sp, color = color, maxLines = 1,
                modifier = Modifier
                    .align(if (alignEnd) Alignment.TopEnd else Alignment.TopStart)
                    .padding(horizontal = 4.dp)
                    .offset(y = (y - 6f).dp),
            )
        }
    }
}

/** 값의 변화를 선으로 그린다. 누르거나 좌우로 끌면 가장 가까운 측정값이 선택되고 위에 값이 나온다 */
@Composable
private fun LineChartPanel(points: List<ChartPoint>, metric: BodyMetric, selected: LocalDate?, onSelect: (LocalDate?) -> Unit) {
    val vals = points.map { it.v }
    val axis = remember(points) { ChartMath.niceAxis(vals.minOrNull() ?: 0f, vals.maxOrNull() ?: 1f) }
    val days = points.map { it.day.toEpochDay() }
    val selIdx = points.indexOfFirst { it.day == selected }.takeIf { it >= 0 }
    val color = metric.color

    Text(
        if (selIdx != null) {
            val p = points[selIdx]
            "${p.day.monthValue}/${p.day.dayOfMonth} · ${metric.label} ${metric.fmt(p.v)}"
        } else "그래프를 누르거나 좌우로 끌어 값을 확인해 보세요",
        fontSize = 11.sp, fontWeight = if (selIdx != null) FontWeight.Bold else FontWeight.Normal,
        color = if (selIdx != null) color else RunColors.TextDim,
    )
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth()) {
        AxisLabels(axis, RunColors.TextDim, alignEnd = true)
        Canvas(
            Modifier
                .weight(1f)
                .height(CHART_H.dp)
                .pointerInput(points, selected) {
                    detectTapGestures { off ->
                        val xs = ChartMath.xPositions(days, size.width.toFloat())
                        val i = ChartMath.nearestIndex(xs, off.x)
                        if (i >= 0) onSelect(if (points[i].day == selected) null else points[i].day)
                    }
                }
                .pointerInput(points) {
                    detectHorizontalDragGestures(
                        onDragStart = { off ->
                            val i = ChartMath.nearestIndex(ChartMath.xPositions(days, size.width.toFloat()), off.x)
                            if (i >= 0) onSelect(points[i].day)
                        },
                    ) { change, _ ->
                        val i = ChartMath.nearestIndex(ChartMath.xPositions(days, size.width.toFloat()), change.position.x)
                        if (i >= 0) onSelect(points[i].day)
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val pad = CHART_PAD.dp.toPx()
            fun y(v: Float) = pad + (1f - axis.frac(v)) * (h - 2 * pad)
            axis.ticks.forEach { t -> drawLine(GRID, Offset(0f, y(t)), Offset(w, y(t)), strokeWidth = 1f) }
            val xs = ChartMath.xPositions(days, w)
            val path = Path()
            points.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(xs[i], y(p.v)) else path.lineTo(xs[i], y(p.v))
            }
            drawPath(path, color, style = Stroke(width = 3f))
            if (points.size <= 80) points.forEachIndexed { i, p -> drawCircle(color, 3.5f, Offset(xs[i], y(p.v))) }
            if (selIdx != null) {
                drawLine(color.copy(alpha = 0.6f), Offset(xs[selIdx], 0f), Offset(xs[selIdx], h), strokeWidth = 2f)
                drawCircle(Color.White, 8f, Offset(xs[selIdx], y(points[selIdx].v)))
                drawCircle(color, 5f, Offset(xs[selIdx], y(points[selIdx].v)))
            }
        }
    }
    Spacer(Modifier.height(2.dp))
    Row(Modifier.fillMaxWidth().padding(start = 34.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${points.first().day}", fontSize = 10.sp, color = RunColors.TextDim)
        Text("${points.last().day}", fontSize = 10.sp, color = RunColors.TextDim)
    }
}

/** 막대(주간 러닝 거리, 왼쪽 축) 위에 선(신체 항목의 주간 평균, 오른쪽 축)을 겹쳐 그린다. 누르면 그 주의 값이 위에 나온다 */
@Composable
private fun ComboChartPanel(weeks: List<BodyWeek>, metric: BodyMetric, selectedDay: LocalDate?, onSelect: (LocalDate?) -> Unit) {
    val n = weeks.size
    val km = weeks.map { it.runKm.toFloat() }
    val avg = weeks.map { w -> w.items.mapNotNull { metric.value(it) }.let { if (it.isEmpty()) null else it.average().toFloat() } }
    val kmAxis = remember(weeks) { ChartMath.niceAxis(0f, km.maxOrNull() ?: 1f, zeroBased = true) }
    val vals = avg.filterNotNull()
    val lineAxis = remember(weeks, metric) {
        if (vals.isEmpty()) null else ChartMath.niceAxis(vals.minOrNull()!!, vals.maxOrNull()!!)
    }
    val selIdx = weeks.indexOfFirst { selectedDay != null && !selectedDay.isBefore(it.monday) && !selectedDay.isAfter(it.monday.plusDays(6)) }
        .takeIf { it >= 0 }
    val color = metric.color

    Text(
        if (selIdx != null) {
            val w = weeks[selIdx]
            val a = avg[selIdx]
            "${w.monday.monthValue}/${w.monday.dayOfMonth}~${w.monday.plusDays(6).monthValue}/${w.monday.plusDays(6).dayOfMonth} 주 · 러닝 ${"%.1f".format(w.runKm)}km · " +
                (if (a != null) "${metric.label} ${metric.fmt(a)}(주 평균, 기록 ${w.items.size}회)" else "${metric.label} 기록 없음")
        } else "막대 = 주간 러닝 거리(왼쪽 축), 선 = ${metric.label} 주 평균(오른쪽 축). 눌러서 확인해 보세요",
        fontSize = 11.sp, fontWeight = if (selIdx != null) FontWeight.Bold else FontWeight.Normal,
        color = if (selIdx != null) Color.White else RunColors.TextDim, lineHeight = 15.sp,
    )
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth()) {
        AxisLabels(kmAxis, KM_COLOR, alignEnd = true)
        Canvas(
            Modifier
                .weight(1f)
                .height(CHART_H.dp)
                .pointerInput(weeks, selectedDay) {
                    detectTapGestures { off ->
                        val i = ChartMath.slotIndex(off.x, n, size.width.toFloat())
                        if (i >= 0) onSelect(if (i == selIdx) null else weeks[i].monday)
                    }
                }
                .pointerInput(weeks) {
                    detectHorizontalDragGestures(
                        onDragStart = { off ->
                            val i = ChartMath.slotIndex(off.x, n, size.width.toFloat())
                            if (i >= 0) onSelect(weeks[i].monday)
                        },
                    ) { change, _ ->
                        val i = ChartMath.slotIndex(change.position.x, n, size.width.toFloat())
                        if (i >= 0) onSelect(weeks[i].monday)
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val pad = CHART_PAD.dp.toPx()
            fun yKm(v: Float) = pad + (1f - kmAxis.frac(v)) * (h - 2 * pad)
            kmAxis.ticks.forEach { t -> drawLine(GRID, Offset(0f, yKm(t)), Offset(w, yKm(t)), strokeWidth = 1f) }
            val slot = w / n
            km.forEachIndexed { i, v ->
                val top = yKm(v)
                if (v > 0f) {
                    drawRect(
                        color = KM_COLOR.copy(alpha = if (i == selIdx) 0.95f else 0.5f),
                        topLeft = Offset(i * slot + slot * 0.15f, top),
                        size = Size(slot * 0.7f, (h - pad) - top),
                    )
                }
            }
            if (lineAxis != null && vals.size >= 2) {
                fun yLine(v: Float) = pad + (1f - lineAxis.frac(v)) * (h - 2 * pad)
                val path = Path()
                var started = false
                avg.forEachIndexed { i, v ->
                    if (v != null) {
                        val x = ChartMath.slotCenter(i, n, w)
                        if (!started) { path.moveTo(x, yLine(v)); started = true } else path.lineTo(x, yLine(v))
                        if (n <= 60) drawCircle(color, 3.5f, Offset(x, yLine(v)))
                    }
                }
                drawPath(path, color, style = Stroke(width = 3f))
                if (selIdx != null) avg[selIdx]?.let { v ->
                    drawCircle(Color.White, 8f, Offset(ChartMath.slotCenter(selIdx, n, w), yLine(v)))
                    drawCircle(color, 5f, Offset(ChartMath.slotCenter(selIdx, n, w), yLine(v)))
                }
            }
            if (selIdx != null) {
                val x = ChartMath.slotCenter(selIdx, n, w)
                drawLine(Color.White.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, h), strokeWidth = 2f)
            }
        }
        if (lineAxis != null) AxisLabels(lineAxis, color, alignEnd = false)
    }
    Spacer(Modifier.height(2.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 34.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${weeks.first().monday}", fontSize = 10.sp, color = RunColors.TextDim)
        Text("${weeks.last().monday}", fontSize = 10.sp, color = RunColors.TextDim)
    }
}

// ───────────────────────── 활동 달력 ─────────────────────────

private val HEAT_ALPHA = floatArrayOf(0.07f, 0.28f, 0.48f, 0.72f, 0.95f)

/**
 * 날마다 러닝 거리를 색 농도(칸)로, 신체를 측정한 날을 점으로 보여 준다. 열 = 주(월요일 시작), 행 = 요일.
 * 칸을 누르면 그날의 러닝과 측정값이 아래에 나오고, 이력의 같은 날짜가 강조된다.
 */
@Composable
private fun CalendarCard(
    list: List<BodyInfo>,
    runs: List<RunSession>,
    metric: BodyMetric,
    today: LocalDate,
    weeksN: Int,
    selDay: LocalDate?,
    onSelect: (LocalDate?) -> Unit,
) {
    val weeks = minOf(weeksN, 26)     // 칸이 너무 작아지지 않게 최근 26주까지만
    val firstMon = PlanEngine.mondayOf(today).minusWeeks((weeks - 1).toLong())
    val runKm = remember(runs) { runs.groupBy { it.date }.mapValues { (_, v) -> v.sumOf { it.distanceKm } } }
    val bodyByDay = remember(list) { list.associateBy { it.date } }
    val maxKm = remember(runKm, firstMon, weeks) {
        (0 until weeks * 7).maxOfOrNull { runKm[firstMon.plusDays(it.toLong())] ?: 0.0 } ?: 0.0
    }
    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("활동 달력")
        Spacer(Modifier.height(2.dp))
        Text(
            "칸 하나 = 하루 (진할수록 많이 달린 날), 점 = 신체를 측정한 날. 칸을 누르면 그날 기록이 나와요.",
            fontSize = 10.sp, color = RunColors.TextDim, lineHeight = 14.sp,
        )
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val labelW = 22.dp
            val gap = 2.dp
            val cell = (maxWidth - labelW - gap * (weeks - 1)) / weeks
            val h = cell * 7 + gap * 6
            Row {
                Column(Modifier.width(labelW).height(h), verticalArrangement = Arrangement.spacedBy(gap)) {
                    listOf("월", "", "수", "", "금", "", "일").forEach {
                        Text(it, fontSize = 8.sp, color = RunColors.TextDim, maxLines = 1, modifier = Modifier.height(cell))
                    }
                }
                Canvas(
                    Modifier
                        .width(cell * weeks + gap * (weeks - 1))
                        .height(h)
                        .pointerInput(weeks, firstMon, today, selDay) {
                            detectTapGestures { off ->
                                val hit = ChartMath.heatHit(off.x, off.y, cell.toPx(), gap.toPx(), weeks, 7)
                                if (hit != null) {
                                    val d = firstMon.plusDays((hit.first * 7 + hit.second).toLong())
                                    if (!d.isAfter(today)) onSelect(if (d == selDay) null else d)
                                }
                            }
                        },
                ) {
                    val c = cell.toPx()
                    val g = gap.toPx()
                    for (col in 0 until weeks) {
                        for (row in 0 until 7) {
                            val d = firstMon.plusDays((col * 7 + row).toLong())
                            if (d.isAfter(today)) continue
                            val lv = ChartMath.heatLevel(runKm[d] ?: 0.0, maxKm)
                            val tl = Offset(col * (c + g), row * (c + g))
                            drawRoundRect(KM_COLOR.copy(alpha = HEAT_ALPHA[lv]), tl, Size(c, c), CornerRadius(c * 0.2f))
                            if (bodyByDay.containsKey(d)) drawCircle(metric.color, c * 0.2f, Offset(tl.x + c / 2, tl.y + c / 2))
                            if (d == selDay) drawRoundRect(Color.White, tl, Size(c, c), CornerRadius(c * 0.2f), style = Stroke(width = 2f))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val sd = selDay
        if (sd != null && !sd.isBefore(firstMon) && !sd.isAfter(today)) {
            val b = bodyByDay[sd]
            val body = if (b != null) {
                BodyMetric.values().mapNotNull { m -> m.value(b)?.let { m.label + " " + m.fmt(it) } }.joinToString(" · ")
            } else "측정 기록 없음"
            Text(
                "${sd.monthValue}/${sd.dayOfMonth}(${korDow(sd.dayOfWeek)}) · 러닝 ${"%.1f".format(runKm[sd] ?: 0.0)}km · $body",
                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, lineHeight = 15.sp,
            )
        } else {
            Text("최근 ${weeks}주 ($firstMon ~ $today)", fontSize = 10.sp, color = RunColors.TextDim)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("적음", fontSize = 9.sp, color = RunColors.TextDim)
            Spacer(Modifier.width(4.dp))
            HEAT_ALPHA.forEach { a ->
                Box(Modifier.padding(horizontal = 1.dp).size(10.dp).clip(RoundedCornerShape(2.dp)).background(KM_COLOR.copy(alpha = a)))
            }
            Spacer(Modifier.width(4.dp))
            Text("많음", fontSize = 9.sp, color = RunColors.TextDim)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(metric.color))
            Spacer(Modifier.width(4.dp))
            Text("측정일", fontSize = 9.sp, color = RunColors.TextDim)
        }
    }
}

// ───────────────────────── 이력 ─────────────────────────

private class MonthData(val ym: YearMonth, val records: List<BodyInfo>, val km: Double, val runCount: Int, val weeks: List<Double>)

/**
 * 측정 이력을 달마다 카드로 묶어 보여 준다. 월 카드에는 러닝 거리·횟수, 선택한 항목의 변화와 작은 추이 그래프, 주간 러닝 막대가 있고,
 * 펼치면 측정 기록 카드가 나온다 (값, 이전 측정 대비 변화, 전체 이력 중 내 위치 막대, 다른 항목, 그 주 러닝 거리).
 */
@Composable
private fun HistoryCard(
    list: List<BodyInfo>,
    runs: List<RunSession>,
    metric: BodyMetric,
    selDay: LocalDate?,
    onSelect: (LocalDate?) -> Unit,
) {
    var shownMonths by remember { mutableStateOf(6) }
    var userOpen by remember { mutableStateOf<Set<YearMonth>?>(null) }
    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("이력")
        Spacer(Modifier.height(2.dp))
        Text(
            "달마다 묶어 보여요. 기록 카드의 막대는 전체 이력의 최저~최고 안에서 내 값의 위치예요.",
            fontSize = 10.sp, color = RunColors.TextDim, lineHeight = 14.sp,
        )
        if (list.isEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("기록이 없어요", fontSize = 11.sp, color = RunColors.TextDim)
            return@GlassCard
        }
        val asc = remember(list) { list.sortedBy { it.date.toEpochDay() } }
        val weekKm = remember(runs) {
            val m = HashMap<LocalDate, Double>()
            runs.forEach { r ->
                val mon = PlanEngine.mondayOf(r.date)
                m[mon] = (m[mon] ?: 0.0) + r.distanceKm
            }
            m
        }
        val months = remember(list, runs) {
            val byMonth = runs.groupBy { YearMonth.from(it.date) }
            asc.map { YearMonth.from(it.date) }.distinct().sortedDescending().map { ym ->
                val last = ym.atEndOfMonth()
                var mon = PlanEngine.mondayOf(ym.atDay(1))
                val wk = ArrayList<Double>()
                while (!mon.isAfter(last)) {
                    wk += weekKm[mon] ?: 0.0
                    mon = mon.plusWeeks(1)
                }
                MonthData(
                    ym, asc.filter { YearMonth.from(it.date) == ym },
                    byMonth[ym]?.sumOf { it.distanceKm } ?: 0.0, byMonth[ym]?.size ?: 0, wk,
                )
            }
        }
        val range = remember(list) {
            BodyMetric.values().associateWith { m ->
                val v = list.mapNotNull { m.value(it) }
                if (v.isEmpty()) null else (v.minOrNull()!! to v.maxOrNull()!!)
            }
        }
        val maxWeekKm = remember(weekKm) { (weekKm.values.maxOrNull() ?: 0.0).coerceAtLeast(1.0) }
        val selYm = selDay?.let { YearMonth.from(it) }
        val open: Set<YearMonth> = userOpen ?: setOfNotNull(months.firstOrNull()?.ym, selYm)

        months.take(shownMonths).forEach { md ->
            val isOpen = md.ym in open
            MonthHeader(md, metric, isOpen) { userOpen = if (isOpen) open - md.ym else open + md.ym }
            if (isOpen) {
                md.records.reversed().forEach { b ->
                    val prev = BodyMetric.values().associateWith { m ->
                        asc.lastOrNull { it.date.isBefore(b.date) && m.value(it) != null }?.let { m.value(it) }
                    }
                    val selected = selDay != null && (b.date == selDay || PlanEngine.mondayOf(b.date) == selDay)
                    RecordCard(
                        b, prev, metric, range, weekKm[PlanEngine.mondayOf(b.date)] ?: 0.0, maxWeekKm,
                        selected, selected && b.date == selDay,
                    ) { onSelect(if (b.date == selDay) null else b.date) }
                }
            }
        }
        if (months.size > shownMonths) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { shownMonths += 6 },
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            ) { Text("이전 달 더 보기 (${months.size - shownMonths}개월)", fontSize = 11.sp, color = RunColors.Lime) }
        }
    }
}

@Composable
private fun Pill(text: String, color: Color = RunColors.TextDim) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.09f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) { Text(text, fontSize = 10.sp, color = color, maxLines = 1) }
}

@Composable
private fun MonthHeader(md: MonthData, metric: BodyMetric, open: Boolean, onToggle: () -> Unit) {
    val vals = md.records.mapNotNull { metric.value(it) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable(onClick = onToggle)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${md.ym.year}년 ${md.ym.monthValue}월", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            Text(if (open) "접기 ▴" else "펼치기 ▾", fontSize = 10.sp, color = RunColors.TextDim)
        }
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("러닝 ${"%.0f".format(md.km)}km · ${md.runCount}회")
            Pill("측정 ${md.records.size}회")
        }
        if (vals.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (vals.size >= 2) "${metric.label} ${metric.fmt(vals.first())} → ${metric.fmt(vals.last())}" else "${metric.label} ${metric.fmt(vals.first())}",
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = metric.color, modifier = Modifier.weight(1f),
                )
                if (vals.size >= 2) fmtDelta(vals.last(), vals.first(), metric)?.let { Pill(it) }
            }
            if (vals.size >= 2) {
                Spacer(Modifier.height(4.dp))
                Sparkline(vals, metric.color)
            }
        }
        if (md.weeks.any { it > 0.0 }) {
            Spacer(Modifier.height(6.dp))
            Text("주간 러닝 거리", fontSize = 9.sp, color = RunColors.TextDim)
            WeeklyBars(md.weeks)
        }
    }
}

/** 작은 추이 그래프 (눈금 없이 모양만) */
@Composable
private fun Sparkline(values: List<Float>, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(34.dp)) {
        if (values.size < 2) return@Canvas
        val lo = values.minOrNull() ?: return@Canvas
        val hi = values.maxOrNull() ?: return@Canvas
        val span = (hi - lo).coerceAtLeast(0.3f)
        val pad = 4.dp.toPx()
        val stepX = size.width / (values.size - 1)
        fun y(v: Float) = pad + (1f - (v - lo) / span) * (size.height - 2 * pad)
        val path = Path()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(0f, y(v)) else path.lineTo(stepX * i, y(v)) }
        drawPath(path, color, style = Stroke(width = 3f))
        values.forEachIndexed { i, v -> drawCircle(color, 3.5f, Offset(stepX * i, y(v))) }
    }
}

/** 한 달 동안의 주간 러닝 거리를 막대로 */
@Composable
private fun WeeklyBars(weeks: List<Double>) {
    Canvas(Modifier.fillMaxWidth().height(26.dp)) {
        val n = weeks.size
        if (n == 0) return@Canvas
        val maxV = (weeks.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
        val slot = size.width / n
        weeks.forEachIndexed { i, v ->
            val h = (v / maxV).toFloat() * size.height
            if (h > 0f) {
                drawRoundRect(
                    KM_COLOR.copy(alpha = 0.6f),
                    Offset(i * slot + slot * 0.18f, size.height - h),
                    Size(slot * 0.64f, h),
                    CornerRadius(3f),
                )
            }
        }
    }
}

/** 값이 전체 이력의 최저~최고 중 어디쯤인지 점으로 보여 준다 */
@Composable
private fun PositionBar(frac: Float, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val r = 4.dp.toPx()
        val track = 3.dp.toPx()
        drawRoundRect(
            Color.White.copy(alpha = 0.14f),
            Offset(0f, size.height / 2 - track / 2),
            Size(size.width, track),
            CornerRadius(track / 2),
        )
        drawCircle(color, r, Offset(r + frac * (size.width - 2 * r), size.height / 2))
    }
}

@Composable
private fun RecordCard(
    b: BodyInfo,
    prev: Map<BodyMetric, Float?>,
    metric: BodyMetric,
    range: Map<BodyMetric, Pair<Float, Float>?>,
    weekKm: Double,
    maxWeekKm: Double,
    highlighted: Boolean,
    showDetail: Boolean,
    onClick: () -> Unit,
) {
    val v = metric.value(b)
    val others = BodyMetric.values().filter { it != metric && it.value(b) != null }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (highlighted) metric.color.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.03f))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.width(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${b.date.dayOfMonth}", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Text("${b.date.monthValue}월", fontSize = 9.sp, color = RunColors.TextDim)
            Text(korDow(b.date.dayOfWeek), fontSize = 9.sp, color = RunColors.TextDim)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (v != null) metric.fmt(v) else "${metric.label} -",
                    fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = metric.color,
                )
                fmtDelta(v, prev[metric], metric)?.let {
                    Spacer(Modifier.width(6.dp))
                    Pill(it)
                }
            }
            val r = range[metric]
            if (v != null && r != null) {
                Spacer(Modifier.height(2.dp))
                PositionBar(ChartMath.position(v, r.first, r.second), metric.color)
            }
            if (others.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    others.forEach { m ->
                        Column {
                            Text(m.label, fontSize = 8.sp, color = RunColors.TextDim)
                            Text(m.fmt(m.value(b)!!), fontSize = 11.sp, color = Color.White)
                            Text(fmtDelta(m.value(b), prev[m], m) ?: " ", fontSize = 8.sp, color = RunColors.TextDim)
                        }
                    }
                }
            }
            if (showDetail) {
                val parts = BodyMetric.values().mapNotNull { m ->
                    val cur = m.value(b)
                    val p = prev[m]
                    if (cur != null && p != null) ChartMath.deltaText(m.label, cur, p, m.deltaUnit) else null
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (parts.isEmpty()) "이전 측정이 없어서 비교할 수 없어요" else "이전 측정 대비: " + parts.joinToString(" · "),
                    fontSize = 10.sp, color = RunColors.TextDim, lineHeight = 14.sp,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.width(54.dp), horizontalAlignment = Alignment.End) {
            Text("이 주 러닝", fontSize = 8.sp, color = RunColors.TextDim)
            Text("${"%.0f".format(weekKm)}km", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.1f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((weekKm / maxWeekKm).toFloat().coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(KM_COLOR),
                )
            }
        }
    }
}
