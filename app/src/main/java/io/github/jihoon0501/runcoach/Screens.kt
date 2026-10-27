package io.github.jihoon0501.runcoach

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import java.time.temporal.ChronoUnit
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth

enum class SyncState { Idle, Loading, Ready, NeedPermission, Unavailable, Error }

// ───────────────────────── 공용 컴포넌트 (컴팩트) ─────────────────────────

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    var m = modifier
        .clip(shape)
        .background(Color.White.copy(alpha = 0.06f))
        .border(1.dp, Color.White.copy(alpha = 0.09f), shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(horizontal = 12.dp, vertical = 10.dp), content = content)
}

@Composable
fun TypeChip(type: WorkoutType) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(type.color.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text("${type.emoji} ${type.label}", color = type.color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
        Text(label, fontSize = 10.sp, color = RunColors.TextDim, maxLines = 1)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = modifier)
}

@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 10.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val anim by animateFloatAsState(progress.coerceIn(0f, 1f), animationSpec = tween(1200), label = "ring")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(stroke / 2, stroke / 2)
            drawArc(
                color = Color.White.copy(alpha = 0.08f),
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            rotate(-90f) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(RunColors.Violet, RunColors.Pink, RunColors.Lime)),
                    startAngle = 0f, sweepAngle = 360f * anim, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        content()
    }
}

@Composable
fun PaceChart(runs: List<RunSession>, modifier: Modifier = Modifier) {
    if (runs.isEmpty()) return
    val paces = runs.map { it.paceSecPerKm }
    val minP = paces.min()
    val maxP = paces.max()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(runs) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(900))
    }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().height(80.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            runs.forEach { r ->
                val frac = if (maxP - minP < 1.0) 0.7f
                else 0.35f + 0.65f * ((maxP - r.paceSecPerKm) / (maxP - minP)).toFloat()
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(formatPace(r.paceSecPerKm), fontSize = 8.sp, color = RunColors.TextDim, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.75f * frac * progress.value)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Brush.verticalGradient(listOf(RunColors.Lime, RunColors.Violet)))
                    )
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            runs.forEach { r ->
                Text(
                    "${r.date.monthValue}/${r.date.dayOfMonth}",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = 9.sp,
                    color = RunColors.TextDim,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 홈 카드용 막대 차트. mode 0=페이스, 1=러닝 시간, 2=평균 심박수 (카드를 누를 때마다 전환) */
@Composable
fun MetricChart(runs: List<RunSession>, mode: Int, modifier: Modifier = Modifier) {
    if (runs.isEmpty()) return
    fun value(r: RunSession): Double? = when (mode) {
        0 -> r.paceSecPerKm.takeIf { it > 0 }
        1 -> r.durationSec.toDouble()
        else -> r.avgHr?.toDouble()
    }
    fun label(r: RunSession): String = when (mode) {
        0 -> formatPace(r.paceSecPerKm)
        1 -> formatDuration(r.durationSec)
        else -> r.avgHr?.toString() ?: "-"
    }
    val vals = runs.map { value(it) }
    val present = vals.filterNotNull()
    if (present.isEmpty()) return
    val minV = present.min()
    val maxV = present.max()
    val colors = when (mode) {
        0 -> listOf(RunColors.Lime, RunColors.Violet)
        1 -> listOf(RunColors.Blue, RunColors.Violet)
        else -> listOf(RunColors.Pink, RunColors.Orange)
    }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(runs, mode) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(700))
    }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().weight(1f).heightIn(min = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            runs.forEachIndexed { i, r ->
                val v = vals[i]
                val frac = when {
                    v == null -> 0.1f
                    maxV - minV < 1e-6 -> 0.7f
                    mode == 0 -> 0.35f + 0.65f * ((maxV - v) / (maxV - minV)).toFloat()   // 페이스는 빠를수록 높게
                    else -> 0.35f + 0.65f * ((v - minV) / (maxV - minV)).toFloat()
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(label(r), fontSize = 8.sp, color = RunColors.TextDim, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.75f * frac * progress.value)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Brush.verticalGradient(colors))
                    )
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            runs.forEach { r ->
                Text(
                    "${r.date.monthValue}/${r.date.dayOfMonth}",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = 9.sp,
                    color = RunColors.TextDim,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun SplitBars(splits: List<Int>, modifier: Modifier = Modifier) {
    if (splits.size < 2) return
    val minS = splits.min()
    val maxS = splits.max()
    Column(modifier) {
        Text("구간별 페이스 (1km)", color = RunColors.TextDim, fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().height(54.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            splits.forEach { sec ->
                val frac = if (maxS - minS < 3) 0.7f
                else 0.35f + 0.65f * (maxS - sec).toFloat() / (maxS - minS).toFloat()
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text("${sec / 60}:${"%02d".format(sec % 60)}", fontSize = 7.sp, color = RunColors.TextDim, maxLines = 1)
                    Spacer(Modifier.height(1.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.68f * frac)
                            .clip(RoundedCornerShape(4.dp))
                            .background(paceColor(sec.toDouble()).copy(alpha = 0.85f))
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            splits.indices.forEach { i ->
                Text("${i + 1}", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 8.sp, color = RunColors.TextDim)
            }
        }
    }
}

fun segColor(g: RunSegment): Color = when (g.role) {
    SegRole.MAIN -> RunColors.Lime
    SegRole.RECOVERY -> RunColors.Orange
    else -> RunColors.Blue   // 준비운동 / 마무리
}

@Composable
fun SegmentTimeline(segments: List<RunSegment>, modifier: Modifier = Modifier) {
    if (segments.size < 2) return
    Column(modifier) {
        Text("운동 구성", color = RunColors.TextDim, fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp)),
        ) {
            segments.forEach { g ->
                Box(
                    Modifier
                        .weight(g.durSec.coerceAtLeast(1L).toFloat())
                        .fillMaxHeight()
                        .background(segColor(g))
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        segments.forEach { g ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(segColor(g)))
                Spacer(Modifier.width(6.dp))
                Text(g.label, color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(
                    "%.2fkm · %s · %s".format(g.distanceKm, formatDuration(g.durSec), formatPace(g.paceSecPerKm)),
                    color = RunColors.TextDim, fontSize = 10.sp,
                )
            }
        }
    }
}

fun paceColor(pace: Double): Color = when {
    pace < 300 -> RunColors.Pink
    pace < 345 -> RunColors.Orange
    else -> RunColors.Green
}

@Composable
fun ConnectCard(state: SyncState, errorMsg: String, onConnect: () -> Unit, onRefresh: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        when (state) {
            SyncState.Loading, SyncState.Idle -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = RunColors.Lime, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("기록을 불러오는 중...", color = Color.White, fontSize = 12.sp)
                }
            }
            SyncState.NeedPermission -> {
                Text("🔗 삼성 헬스 연결", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Spacer(Modifier.height(3.dp))
                Text(
                    "러닝 기록을 읽기 전용으로 가져와요. 삼성 헬스 개발자 모드의 데이터 읽기가 켜져 있어야 해요.",
                    color = RunColors.TextDim, fontSize = 11.sp,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onConnect,
                    colors = ButtonDefaults.buttonColors(containerColor = RunColors.Lime, contentColor = RunColors.Bg),
                    modifier = Modifier.fillMaxWidth().height(38.dp),
                ) { Text("연결하기", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            }
            SyncState.Unavailable -> {
                Text("삼성 헬스를 사용할 수 없어요", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Spacer(Modifier.height(3.dp))
                Text("삼성 헬스 앱을 최신 버전으로 업데이트한 뒤 다시 열어주세요.", color = RunColors.TextDim, fontSize = 11.sp)
            }
            SyncState.Error -> {
                Text("불러오지 못했어요", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Spacer(Modifier.height(3.dp))
                Text(errorMsg, color = RunColors.Pink, fontSize = 10.sp)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onRefresh, modifier = Modifier.height(34.dp)) {
                    Text("다시 시도", color = RunColors.Lime, fontSize = 12.sp)
                }
            }
            SyncState.Ready -> Unit
        }
    }
}

/** 주간 목표 링: 실제 진행(채움) + '오늘까지 계획' 위치(흰 눈금)를 함께 보여줘서 앞서는지 뒤처지는지 바로 보이게 한다. */
@Composable
fun GoalRing(
    progress: Float,
    expected: Float,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 10.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val anim by animateFloatAsState(progress.coerceIn(0f, 1f), animationSpec = tween(1000), label = "goal")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(stroke / 2, stroke / 2)
            drawArc(
                color = Color.White.copy(alpha = 0.09f),
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(stroke),
            )
            drawArc(
                color = color,
                startAngle = -90f, sweepAngle = 360f * anim, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            val e = expected.coerceIn(0f, 1f)
            if (e > 0f && e < 1f) {
                val ang = Math.toRadians(-90.0 + 360.0 * e)
                val r = (size.width - stroke) / 2f
                val cx = size.width / 2f
                val cy = size.height / 2f
                val inner = r - stroke * 0.8f
                val outer = r + stroke * 0.8f
                drawLine(
                    color = Color.White,
                    start = Offset(cx + (inner * Math.cos(ang)).toFloat(), cy + (inner * Math.sin(ang)).toFloat()),
                    end = Offset(cx + (outer * Math.cos(ang)).toFloat(), cy + (outer * Math.sin(ang)).toFloat()),
                    strokeWidth = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
        content()
    }
}

/**
 * 좌우로 밀어서 값을 바꾸는 영역. 왼쪽으로 밀면 value + 1, 오른쪽으로 밀면 value - 1.
 * 바뀔 때 슬라이드 애니메이션이 나온다. 값은 [min]~[max] 범위 안에서만 바뀐다.
 */
@Composable
fun SwipeSwitcher(
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clipToBounds()
            .pointerInput(value, min, max) {
                var total = 0f
                val threshold = 56.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragCancel = { total = 0f },
                    onDragEnd = {
                        if (total <= -threshold && value < max) onChange(value + 1)
                        else if (total >= threshold && value > min) onChange(value - 1)
                        total = 0f
                    },
                    onHorizontalDrag = { change, dx ->
                        total += dx
                        change.consume()
                    },
                )
            }
    ) {
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally(tween(240)) { it } + fadeIn(tween(240))) togetherWith
                        (slideOutHorizontally(tween(240)) { -it } + fadeOut(tween(180)))
                } else {
                    (slideInHorizontally(tween(240)) { -it } + fadeIn(tween(240))) togetherWith
                        (slideOutHorizontally(tween(240)) { it } + fadeOut(tween(180)))
                }
            },
            label = "swipe",
        ) { v -> content(v) }
    }
}

/** 밀 수 있는 카드의 제목줄. 이동할 수 있는 쪽에만 라임색 화살표가 보인다. */
@Composable
private fun SwipeTitle(title: String, canPrev: Boolean, canNext: Boolean, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("‹", fontSize = 15.sp, color = if (canPrev) RunColors.Lime else Color.Transparent, modifier = Modifier.padding(end = 5.dp))
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1)
        trailing()
        Text("›", fontSize = 15.sp, color = if (canNext) RunColors.Lime else Color.Transparent, modifier = Modifier.padding(start = 5.dp))
    }
}

// ───────────────────────── 홈 (한 화면 대시보드) ─────────────────────────

@Composable
fun HomeScreen(
    runs: List<RunSession>,
    today: LocalDate,
    state: SyncState,
    errorMsg: String,
    plan: List<PlanItem>,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    syncing: Boolean = false,
    readiness: Readiness? = null,
    onShowReadiness: () -> Unit = {},
) {
    val weekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val weekEnd = weekStart.plusDays(6)
    val weekKm = runs.filter { it.date in weekStart..weekEnd }.sumOf { it.distanceKm }
    val weekCount = runs.count { it.date in weekStart..weekEnd }
    val planTarget = plan.filter { it.date in weekStart..weekEnd && !it.optional }.sumOf { it.km }
    // 이번 주 계획이 없으면 최근 4주 평균 주간 거리(+5%)를 목표로 삼는다
    val recentWeekly = runs.filter { it.date >= today.minusDays(28) }.sumOf { it.distanceKm } / 4.0 * 1.05
    val target = if (planTarget > 0) planTarget else if (recentWeekly > 0) recentWeekly else 30.0
    val plannedToDate = if (planTarget > 0) {
        plan.filter { it.date in weekStart..weekEnd && !it.optional && it.date <= today }.sumOf { it.km }
    } else {
        target * today.dayOfWeek.value / 7.0
    }
    val weekDiff = weekKm - plannedToDate
    val weekPct = Math.round(weekKm / target * 100).toInt()
    val weekStatus = when {
        weekKm >= target -> "목표 달성 🎉"
        plannedToDate <= 0.0 && weekKm <= 0.0 -> "이번 주 첫 훈련 전이에요"
        weekDiff >= 1.0 -> "%.1fkm 앞서고 있어요".format(weekDiff)
        weekDiff >= -1.0 -> "계획대로 순항 중 👍"
        else -> "%.1fkm 뒤처졌어요".format(-weekDiff)
    }
    val weekColor = if (weekDiff >= -1.0) RunColors.Lime else RunColors.Orange
    val matches = remember(plan, runs) { PlanEngine.match(plan, runs) }
    val thisMonth = YearMonth.from(today)
    // 좌우로 이동할 수 있는 범위: 가장 오래된 기록/계획 ~ 마지막 계획
    val earliest = listOfNotNull(runs.minOfOrNull { it.date }, plan.minOfOrNull { it.date }).minOrNull() ?: today
    val latestPlan = plan.maxOfOrNull { it.date } ?: today
    val earliestMonday = earliest.minusDays((earliest.dayOfWeek.value - 1).toLong())
    val latestMonday = latestPlan.minusDays((latestPlan.dayOfWeek.value - 1).toLong())
    val weekMin = -(((weekStart.toEpochDay() - earliestMonday.toEpochDay()) / 7).toInt().coerceAtLeast(0))
    val weekMax = ((latestMonday.toEpochDay() - weekStart.toEpochDay()) / 7).toInt().coerceAtLeast(1)
    val monthMin = minOf(0, ChronoUnit.MONTHS.between(thisMonth, YearMonth.from(earliest)).toInt())
    val monthMax = maxOf(1, ChronoUnit.MONTHS.between(thisMonth, YearMonth.from(latestPlan)).toInt())
    val next = plan.firstOrNull { it.date >= today && !matches.containsKey(it.id) }
    val todayDone = plan.any { it.date == today && matches.containsKey(it.id) }
    val ranYesterday = runs.any { it.date == today.minusDays(1) }
    val ranDayBefore = runs.any { it.date == today.minusDays(2) }
    val lastRun = runs.lastOrNull()
    var metricMode by rememberSaveable { mutableStateOf(0) }
    var weekOffset by rememberSaveable { mutableStateOf(0) }    // 0 = 이번 주, -1 = 지난주, +1 = 다음 주
    var monthOffset by rememberSaveable { mutableStateOf(0) }   // 0 = 이번 달
    var runPos by rememberSaveable { mutableStateOf(-1) }       // -1 = 가장 최근 러닝, 그 외에는 목록 위치

    Column(
        Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("안녕하세요, ${UserProfile.NAME}님 👋", color = RunColors.TextDim, fontSize = 11.sp)
                Text(
                    "${today.monthValue}월 ${today.dayOfMonth}일 ${korDow(today.dayOfWeek)}요일",
                    fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Color.White,
                )
            }
            IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                if (syncing) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = RunColors.Lime, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "새로고침", tint = RunColors.Lime, modifier = Modifier.size(20.dp))
                }
            }
        }

        if (state != SyncState.Ready) {
            ConnectCard(state, errorMsg, onConnect, onRefresh)
        }

        // 이번 주 링 + 오늘의 러닝 (나란히)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassCard(Modifier.weight(1f).fillMaxHeight()) {
                Text(
                    "이번 주 거리 목표",
                    fontSize = 10.sp, color = RunColors.TextDim,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    GoalRing(
                        progress = (weekKm / target).toFloat(),
                        expected = (plannedToDate / target).toFloat(),
                        color = weekColor,
                        modifier = Modifier.size(94.dp),
                        strokeWidth = 9.dp,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$weekPct%", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                            Text("%.1f/%.0fkm".format(weekKm, target), fontSize = 9.sp, color = RunColors.TextDim)
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    weekStatus,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, color = weekColor,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1,
                )
                Text(
                    "| 오늘까지 계획 %.1fkm".format(plannedToDate),
                    fontSize = 9.sp, color = RunColors.TextDim,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1,
                )
            }

            GlassCard(Modifier.weight(1.25f).fillMaxHeight()) {
                if (next != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (next.date == today) "오늘의 러닝" else dateLabel(next.date, today),
                            fontSize = 11.sp, color = RunColors.TextDim, modifier = Modifier.weight(1f), maxLines = 1,
                        )
                        TypeChip(next.type)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        next.title, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "%.1fkm · 목표 %s/km".format(next.km, formatPace(PlanEngine.targetPace(next).toDouble())),
                        fontSize = 11.sp, color = RunColors.Lime, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    next.tips.take(3).forEach {
                        Text(
                            "•  $it", fontSize = 10.sp, color = RunColors.TextDim,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                } else {
                    Text("예정된 훈련이 없어요", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text("계획 탭에서 새 계획을 만들어 보세요.", fontSize = 11.sp, color = RunColors.TextDim)
                }
                if (todayDone) {
                    Spacer(Modifier.height(8.dp))
                    Text("✓ 오늘 러닝 완료", fontSize = 11.sp, color = RunColors.Lime, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 오늘 컨디션 (수면·심박·에너지 점수 기반)
        if (readiness != null) {
            ReadinessCard(readiness, onShowReadiness)
        }

        // 주간 훈련 (좌우로 밀면 지난주 / 다음 주)
        GlassCard(Modifier.fillMaxWidth()) {
            SwipeSwitcher(weekOffset, weekMin, weekMax, { weekOffset = it }) { off ->
                val ws = weekStart.plusWeeks(off.toLong())
                val we = ws.plusDays(6)
                val doneCnt = plan.count { it.date in ws..we && matches.containsKey(it.id) }
                val totalCnt = plan.count { it.date in ws..we && !it.optional }
                val title = when (off) {
                    0 -> "이번 주 훈련"
                    -1 -> "지난주 훈련"
                    1 -> "다음 주 훈련"
                    else -> "주간 훈련"
                }
                Column(Modifier.fillMaxWidth()) {
                    SwipeTitle(title, off > weekMin, off < weekMax) {
                        Text(
                            "${ws.monthValue}/${ws.dayOfMonth}~${we.monthValue}/${we.dayOfMonth} · 완료 $doneCnt/$totalCnt",
                            fontSize = 10.sp, color = RunColors.TextDim,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth()) {
                        for (i in 0 until 7) {
                            val d = ws.plusDays(i.toLong())
                            val p = plan.firstOrNull { q -> q.date == d }
                            val done = p != null && matches.containsKey(p.id)
                            val ranAny = runs.any { r -> r.date == d }
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    korDow(d.dayOfWeek), fontSize = 10.sp,
                                    color = if (d == today) RunColors.Lime else RunColors.TextDim,
                                    fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal,
                                )
                                Spacer(Modifier.height(3.dp))
                                Box(
                                    Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                done -> RunColors.Lime
                                                p != null -> p.type.color.copy(alpha = 0.22f)
                                                ranAny -> Color.White.copy(alpha = 0.14f)
                                                else -> Color.White.copy(alpha = 0.05f)
                                            }
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        when {
                                            done -> "✓"
                                            p != null -> p.type.emoji
                                            ranAny -> "•"
                                            else -> ""
                                        },
                                        fontSize = 12.sp,
                                        color = if (done) RunColors.Bg else Color.White,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(if (p != null) "${Math.round(p.km)}k" else "-", fontSize = 9.sp, color = RunColors.TextDim)
                            }
                        }
                    }
                }
            }
        }

        // 월 달성률 (좌우로 밀면 전달 / 다음 달)
        GlassCard(Modifier.fillMaxWidth()) {
            SwipeSwitcher(monthOffset, monthMin, monthMax, { monthOffset = it }) { off ->
                val m = thisMonth.plusMonths(off.toLong())
                val a = PlanEngine.achievement(plan, matches, runs, m, today)
                val title = if (m.year == today.year) "${m.monthValue}월 추천 계획 달성률"
                else "${m.year}년 ${m.monthValue}월 추천 계획 달성률"
                Column(Modifier.fillMaxWidth()) {
                    SwipeTitle(title, off > monthMin, off < monthMax) {
                        Text("${a.percent}%", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = RunColors.Lime)
                    }
                    Spacer(Modifier.height(5.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(a.percent / 100f)
                                .fillMaxHeight()
                                .background(Brush.horizontalGradient(listOf(RunColors.Violet, RunColors.Pink, RunColors.Lime)))
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "완료 %.1f / %.1fkm · 계획 훈련 %d/%d회".format(a.doneKm, a.plannedKm, a.doneCount, a.totalCount),
                        fontSize = 10.sp, color = RunColors.TextDim,
                    )
                    if (a.note.isNotBlank()) {
                        Text("※ ${a.note}", fontSize = 9.sp, color = RunColors.TextDim.copy(alpha = 0.8f), lineHeight = 12.sp)
                    }
                }
            }
        }

        // 회복 경고 (필요할 때만, 슬림)
        if (ranYesterday && ranDayBefore) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("🦵 이틀 연속 달렸어요 · 오늘은 휴식이나 가벼운 근력운동을 권해요", color = RunColors.Orange, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // 최근 러닝 (좌우로 밀면 이전 러닝 / 더 최근 러닝)
        if (lastRun != null) {
            val pos = if (runPos < 0) runs.lastIndex else runPos.coerceIn(0, runs.lastIndex)
            GlassCard(Modifier.fillMaxWidth()) {
                SwipeSwitcher(
                    value = pos, min = 0, max = runs.lastIndex,
                    onChange = { runPos = if (it >= runs.lastIndex) -1 else it },
                ) { idx ->
                    val r = runs[idx.coerceIn(0, runs.lastIndex)]
                    Column(Modifier.fillMaxWidth()) {
                        SwipeTitle(
                            (if (idx >= runs.lastIndex) "최근 러닝" else "이전 러닝") + " · ${dateLabel(r.date, today)}",
                            idx > 0, idx < runs.lastIndex,
                        ) {
                            Text(
                                formatPace(r.paceSecPerKm) + "/km",
                                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = paceColor(r.paceSecPerKm),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Stat("거리", "%.2fkm".format(r.distanceKm))
                            Stat("시간", formatDuration(r.durationSec))
                            Stat("평균 심박", r.avgHr?.toString() ?: "-")
                            Stat("케이던스", r.cadence?.toString() ?: "-")
                        }
                    }
                }
            }
            GlassCard(Modifier.fillMaxWidth().weight(1f), onClick = { metricMode = (metricMode + 1) % 3 }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (metricMode) {
                            0 -> "페이스 추이"
                            1 -> "러닝 시간"
                            else -> "평균 심박수"
                        },
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        when (metricMode) {
                            0 -> "막대가 높을수록 빨라요"
                            1 -> "막대가 높을수록 오래 달렸어요"
                            else -> "막대가 높을수록 심박이 높아요"
                        },
                        fontSize = 10.sp, color = RunColors.TextDim,
                    )
                }
                Spacer(Modifier.height(4.dp))
                MetricChart(runs.takeLast(7), metricMode, Modifier.weight(1f))
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    repeat(3) { i ->
                        Box(
                            Modifier
                                .padding(horizontal = 2.dp)
                                .height(5.dp)
                                .width(if (i == metricMode) 14.dp else 5.dp)
                                .clip(CircleShape)
                                .background(if (i == metricMode) RunColors.Lime else Color.White.copy(alpha = 0.2f))
                        )
                    }
                }
            }
        }
    }
}

// ───────────────────────── 기록 (한 줄 요약 + 펼치기) ─────────────────────────

@Composable
fun AiEvalSection(result: AiResult?, loading: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onClick,
                enabled = !loading,
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RunColors.Violet, contentColor = Color.White),
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(13.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    when {
                        loading -> "분석 중..."
                        result == null -> "🤖 AI 평가"
                        else -> "다시 평가"
                    },
                    fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                )
            }
            if (result != null) {
                Spacer(Modifier.width(8.dp))
                Text("평가: ${result.source}", color = RunColors.TextDim, fontSize = 10.sp)
            }
        }
        if (result != null) {
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(RunColors.Violet.copy(alpha = 0.12f))
                    .padding(10.dp)
            ) {
                Text(result.text, color = Color.White, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
fun RunRow(
    r: RunSession,
    result: AiResult?,
    loading: Boolean,
    onAi: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth().animateContentSize(), onClick = { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${r.date.monthValue}/${r.date.dayOfMonth} ${korDow(r.date.dayOfWeek)}",
                fontSize = 11.sp, color = RunColors.TextDim, modifier = Modifier.width(50.dp), maxLines = 1,
            )
            Text(
                "%.1fkm".format(r.distanceKm),
                fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color.White,
                modifier = Modifier.width(66.dp), maxLines = 1,
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(paceColor(r.paceSecPerKm).copy(alpha = 0.18f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(formatPace(r.paceSecPerKm), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = paceColor(r.paceSecPerKm))
            }
            Spacer(Modifier.weight(1f))
            Text("♥${r.avgHr ?: "-"}", fontSize = 11.sp, color = RunColors.TextDim)
            Spacer(Modifier.width(8.dp))
            Text("${r.cadence ?: "-"}spm", fontSize = 11.sp, color = RunColors.TextDim)
            Spacer(Modifier.width(6.dp))
            Text(
                if (result != null) "🤖" else if (expanded) "⌃" else "⌄",
                fontSize = 11.sp, color = RunColors.TextDim,
            )
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("시간", formatDuration(r.durationSec))
                    Stat("평균 심박", r.avgHr?.toString() ?: "-")
                    Stat("최대 심박", r.maxHr?.toString() ?: "-")
                    Stat("케이던스", r.cadence?.toString() ?: "-")
                }
                if (r.segments.size >= 2) {
                    Spacer(Modifier.height(8.dp))
                    SegmentTimeline(r.segments)
                }
                if (r.splits.size >= 2) {
                    Spacer(Modifier.height(8.dp))
                    SplitBars(r.splits)
                }
                Spacer(Modifier.height(8.dp))
                AiEvalSection(result, loading, onAi)
            }
        }
    }
}

// ───────────────────────── 설정 ─────────────────────────

@Composable
fun SettingsScreen(
    state: SyncState,
    runs: List<RunSession>,
    diagnosticJson: () -> String,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    lastSyncText: String = "",
    onFullReload: () -> Unit = {},
    onShareCalendar: () -> Unit = {},
    onProfileSaved: () -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    // 개발용 진단 도구는 디버그 빌드(직접 설치해서 테스트하는 빌드)에서만 보여준다
    val isDebug = (androidx.compose.ui.platform.LocalContext.current.applicationInfo.flags and
        android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("설정", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White) }

        item { ProfileCard(onProfileSaved) }

        item { ReminderCard() }

        item { AiSettingsCard() }

        item { CalendarCard(onShareCalendar) }

        if (isDebug) {
            item { HealthDiagCard() }

            item { SamsungTestCard() }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionTitle("데이터 연결")
                        val label = when (state) {
                            SyncState.Ready -> "✅ 연결됨 · 러닝 ${runs.size}건" +
                                (if (lastSyncText.isNotBlank()) " · 마지막 동기화 $lastSyncText" else "")
                            SyncState.NeedPermission -> "⚠️ 권한 필요"
                            SyncState.Unavailable -> "❌ 삼성 헬스 사용 불가"
                            SyncState.Error -> "❌ 오류"
                            else -> "⏳ 확인 중"
                        }
                        Text(label, color = RunColors.TextDim, fontSize = 11.sp)
                        Text(
                            "전체 다시 불러오기",
                            color = RunColors.Lime, fontSize = 10.sp,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onFullReload).padding(vertical = 3.dp),
                        )
                    }
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RunColors.Lime, contentColor = RunColors.Bg),
                    ) { Text("권한", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = onRefresh,
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    ) { Text("동기화", color = RunColors.Lime, fontSize = 12.sp) }
                }
            }
        }

        if (isDebug) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            SectionTitle("데이터 진단")
                            Text("읽힌 항목을 JSON으로 복사해 Claude 대화에 붙여넣어요.", color = RunColors.TextDim, fontSize = 10.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(diagnosticJson()))
                                copied = true
                            },
                            modifier = Modifier.height(34.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        ) { Text(if (copied) "복사됨 ✓" else "JSON 복사", color = RunColors.Lime, fontSize = 12.sp) }
                    }
                }
            }

        }

        item { AboutCard() }

        item {
            Text(
                "이 앱은 읽기 전용이며 복사·AI 평가 요청 때만 데이터가 밖으로 나가요.",
                color = RunColors.TextDim, fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
