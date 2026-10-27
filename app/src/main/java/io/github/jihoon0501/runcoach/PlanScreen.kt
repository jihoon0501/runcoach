package io.github.jihoon0501.runcoach

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

@Composable
fun PlanScreen(
    plan: List<PlanItem>,
    runs: List<RunSession>,
    today: LocalDate,
    onReschedule: (PlanItem, LocalDate) -> Unit,
    onRegenerate: () -> Unit,
    note: PlanNote? = null,
    onShowNote: () -> Unit = {},
    onShareCalendar: () -> Unit = {},
    walks: List<WalkSession> = emptyList(),
) {
    val matches = remember(plan, runs) { PlanEngine.match(plan, runs) }
    val walkDays = remember(walks) { PlanEngine.walkDays(walks) }
    var listMode by rememberSaveable { mutableStateOf(false) }
    var monthIso by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var selectedIso by rememberSaveable { mutableStateOf(today.toString()) }
    var rescheduleTarget by remember { mutableStateOf<PlanItem?>(null) }

    val ym = YearMonth.parse(monthIso)
    val selected = LocalDate.parse(selectedIso)

    // 월을 옮길 때 선택 날짜도 그 달 안의 날짜로 맞춘다 (이번 달이면 오늘, 아니면 계획/러닝이 있는 첫 날)
    fun goMonth(target: YearMonth) {
        val pick = when {
            YearMonth.from(today) == target -> today
            else -> plan.filter { YearMonth.from(it.date) == target }.minByOrNull { it.date }?.date
                ?: runs.filter { YearMonth.from(it.date) == target }.minByOrNull { it.date }?.date
                ?: target.atDay(1)
        }
        monthIso = target.toString()
        selectedIso = pick.toString()
    }
    val ach = PlanEngine.achievement(plan, matches, runs, ym, today)
    val monthItems = plan.filter { YearMonth.from(it.date) == ym }.sortedBy { it.date }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("훈련 계획", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, modifier = Modifier.weight(1f))
                TextButton(onClick = onShareCalendar, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("캘린더 공유", color = RunColors.Violet, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                TextButton(onClick = onRegenerate, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("새 계획", color = RunColors.Lime, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        if (note != null) {
            item { PlanNoteCard(note, onShowNote) }
        }

        item { AchievementCard(ym, ach) }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ViewToggle(listMode, Modifier.weight(1f)) { listMode = it }
                Spacer(Modifier.width(8.dp))
                MonthNav(
                    ym,
                    onPrev = { goMonth(ym.minusMonths(1)) },
                    onNext = { goMonth(ym.plusMonths(1)) },
                )
            }
        }

        if (!listMode) {
            item {
                // 캘린더와 날짜 상세 전체를 좌우로 밀면 월이 바뀐다 (왼쪽으로 밀면 다음 달, 오른쪽으로 밀면 이전 달)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clipToBounds()
                        .pointerInput(ym) {
                            var total = 0f
                            val threshold = 56.dp.toPx()
                            detectHorizontalDragGestures(
                                onDragStart = { total = 0f },
                                onDragCancel = { total = 0f },
                                onDragEnd = {
                                    if (total <= -threshold) goMonth(ym.plusMonths(1))
                                    else if (total >= threshold) goMonth(ym.minusMonths(1))
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
                        targetState = ym,
                        transitionSpec = {
                            if (targetState > initialState) {
                                (slideInHorizontally(tween(260)) { it } + fadeIn(tween(260))) togetherWith
                                    (slideOutHorizontally(tween(260)) { -it } + fadeOut(tween(200)))
                            } else {
                                (slideInHorizontally(tween(260)) { -it } + fadeIn(tween(260))) togetherWith
                                    (slideOutHorizontally(tween(260)) { it } + fadeOut(tween(200)))
                            }
                        },
                        label = "month",
                    ) { m ->
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CalendarGrid(m, plan, matches, runs, walkDays, today, selected) { selectedIso = it.toString() }
                            DayDetailCard(selected, plan, matches, runs, walkDays, today) { rescheduleTarget = it }
                        }
                    }
                }
            }
        } else {
            if (monthItems.isEmpty()) {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("이 달에는 계획이 없어요. 위의 '새 계획'을 눌러 만들어 보세요.", color = RunColors.TextDim, fontSize = 12.sp)
                    }
                }
            }
            monthItems.groupBy { it.week }.forEach { (week, list) ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(week, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f))
                        Text(
                            "%.0f km".format(list.filter { !it.optional }.sumOf { it.km }),
                            color = RunColors.TextDim, fontSize = 10.sp,
                        )
                    }
                }
                items(list, key = { it.id }) { p ->
                    PlanListCard(p, matches[p.id], walkDays[p.date], today) { rescheduleTarget = it }
                }
            }
        }
    }

    rescheduleTarget?.let { target ->
        RescheduleDialog(
            item = target,
            plan = plan,
            today = today,
            onPick = { d ->
                onReschedule(target, d)
                monthIso = YearMonth.from(d).toString()
                selectedIso = d.toString()
                rescheduleTarget = null
            },
            onDismiss = { rescheduleTarget = null },
        )
    }
}

// ───────────────────────── 상단 달성률 (가로 컴팩트) ─────────────────────────

@Composable
private fun AchievementCard(ym: YearMonth, a: Achievement) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(progress = a.percent / 100f, modifier = Modifier.size(64.dp), strokeWidth = 7.dp) {
                Text("${a.percent}%", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${ym.monthValue}월 추천 계획 달성률", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text("완료 %.1f / %.1fkm".format(a.doneKm, a.plannedKm), fontSize = 11.sp, color = RunColors.Lime, fontWeight = FontWeight.SemiBold)
                Text("계획 훈련 ${a.doneCount}/${a.totalCount}회 · 추천 전체 = 100%", fontSize = 10.sp, color = RunColors.TextDim)
                if (a.note.isNotBlank()) {
                    Text("※ ${a.note}", fontSize = 9.sp, color = RunColors.TextDim.copy(alpha = 0.8f), lineHeight = 12.sp)
                }
            }
        }
    }
}

// ───────────────────────── 뷰 전환 / 월 이동 ─────────────────────────

@Composable
private fun ViewToggle(listMode: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(3.dp)
    ) {
        listOf("📅 캘린더" to false, "☰ 리스트" to true).forEach { (label, mode) ->
            val sel = listMode == mode
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (sel) RunColors.Lime else Color.Transparent)
                    .clickable { onChange(mode) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (sel) RunColors.Bg else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun MonthNav(ym: YearMonth, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev, modifier = Modifier.size(32.dp)) { Text("‹", fontSize = 22.sp, color = RunColors.Lime) }
        Text("${ym.year}.${"%02d".format(ym.monthValue)}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        IconButton(onClick = onNext, modifier = Modifier.size(32.dp)) { Text("›", fontSize = 22.sp, color = RunColors.Lime) }
    }
}

// ───────────────────────── 캘린더 ─────────────────────────

@Composable
private fun CalendarGrid(
    ym: YearMonth,
    plan: List<PlanItem>,
    matches: Map<String, RunSession>,
    runs: List<RunSession>,
    walkDays: Map<LocalDate, WalkDay>,
    today: LocalDate,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
) {
    val dows = listOf("일", "월", "화", "수", "목", "금", "토")
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            dows.forEach { d ->
                Text(
                    d,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    color = if (d == "일") RunColors.Pink.copy(alpha = 0.8f) else RunColors.TextDim,
                    fontSize = 10.sp,
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        val lead = ym.atDay(1).dayOfWeek.value % 7   // 일요일 = 0
        val days = ym.lengthOfMonth()
        val rows = (lead + days + 6) / 7
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val day = r * 7 + c - lead + 1
                    if (day in 1..days) {
                        val date = ym.atDay(day)
                        val item = plan.firstOrNull { it.date == date }
                        val done = item != null && matches.containsKey(item.id)
                        val walkDay = walkDays[date]
                        val walked = walkDay?.recovery == true
                        val status = item?.let { PlanEngine.status(it, done, today, walked) }
                        val extraKm = if (item == null) runs.filter { it.date == date }.sumOf { it.distanceKm } else 0.0
                        DayCell(
                            date = date,
                            item = item,
                            extraKm = extraKm,
                            walkMin = if (walked) (walkDay?.minutes ?: 0) else 0,
                            status = status,
                            isToday = date == today,
                            isSelected = date == selected,
                            modifier = Modifier.weight(1f),
                            onClick = { onSelect(date) },
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    item: PlanItem?,
    extraKm: Double,
    walkMin: Int,
    status: PlanStatus?,
    isToday: Boolean,
    isSelected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(9.dp)
    var m = modifier
        .padding(1.5.dp)
        .height(46.dp)
        .clip(shape)
        .background(
            when {
                item != null -> item.type.color.copy(alpha = 0.16f)
                extraKm > 0 -> RunColors.Lime.copy(alpha = 0.08f)
                walkMin > 0 -> RunColors.Violet.copy(alpha = 0.10f)
                else -> Color.White.copy(alpha = 0.04f)
            }
        )
    if (isSelected) m = m.border(1.5.dp, RunColors.Lime, shape)
    else if (isToday) m = m.border(1.dp, Color.White.copy(alpha = 0.5f), shape)

    Column(
        m.clickable(onClick = onClick).padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "${date.dayOfMonth}",
            fontSize = 10.sp,
            color = if (isToday) RunColors.Lime else Color.White,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
        )
        if (item != null) {
            Text(item.type.emoji, fontSize = 12.sp)
            val missed = status == PlanStatus.MISSED && !item.optional
            Text(
                when {
                    status == PlanStatus.DONE -> "✓"
                    status == PlanStatus.WALKED -> "걷기"
                    missed -> "!"
                    else -> "${Math.round(item.km)}k"
                },
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    status == PlanStatus.DONE -> RunColors.Lime
                    status == PlanStatus.WALKED -> RunColors.Violet
                    missed -> RunColors.Pink
                    else -> RunColors.TextDim
                },
            )
        } else if (extraKm > 0) {
            Text("🏃", fontSize = 12.sp)
            Text("${Math.round(extraKm)}k", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = RunColors.Lime)
        } else if (walkMin > 0) {
            Text("🚶", fontSize = 12.sp)
            Text("${walkMin}분", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = RunColors.Violet)
        } else {
            Spacer(Modifier.height(1.dp))
        }
    }
}

// ───────────────────────── 날짜 상세 ─────────────────────────

@Composable
private fun DayDetailCard(
    date: LocalDate,
    plan: List<PlanItem>,
    matches: Map<String, RunSession>,
    runs: List<RunSession>,
    walkDays: Map<LocalDate, WalkDay>,
    today: LocalDate,
    onReschedule: (PlanItem) -> Unit,
) {
    val item = plan.firstOrNull { it.date == date }
    val dayRuns = runs.filter { it.date == date }
    val walkDay = walkDays[date]
    GlassCard(Modifier.fillMaxWidth().animateContentSize()) {
        if (item == null) {
            Text(dateLabel(date, today), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.height(4.dp))
            if (dayRuns.isEmpty()) {
                Text("예정된 훈련이 없어요. 휴식일이에요 😌", color = RunColors.TextDim, fontSize = 12.sp)
            } else {
                Text("계획에 없던 러닝", color = RunColors.Orange, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                dayRuns.forEach { RunMini(it) }
            }
            if (walkDay != null) {
                Spacer(Modifier.height(6.dp))
                WalkLine(walkDay)
            }
        } else {
            DayBody(
                item, matches[item.id], today, showHeader = true, dateText = dateLabel(date, today),
                walkDay = walkDay, onReschedule = onReschedule,
            )
        }
    }
}

@Composable
private fun StatusBadge(status: PlanStatus, optional: Boolean) {
    val (text, color) = when {
        status == PlanStatus.DONE -> "완료" to RunColors.Lime
        status == PlanStatus.WALKED -> "회복 걷기" to RunColors.Violet
        status == PlanStatus.MISSED && optional -> "건너뜀" to RunColors.TextDim
        status == PlanStatus.MISSED -> "미완료" to RunColors.Pink
        status == PlanStatus.TODAY -> "오늘" to RunColors.Orange
        else -> "예정" to RunColors.TextDim
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RunMini(r: RunSession) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat("거리", "%.2fkm".format(r.distanceKm))
        Stat("페이스", formatPace(r.paceSecPerKm))
        Stat("평균 심박", r.avgHr?.toString() ?: "-")
        Stat("케이던스", r.cadence?.toString() ?: "-")
    }
}

/** 하루 걷기 한 줄 요약 */
@Composable
private fun WalkLine(w: WalkDay) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("🚶", fontSize = 12.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            "걷기 ${w.minutes}분 · %.1fkm".format(w.km) + (w.avgHr?.let { " · ♥$it" } ?: "") + (if (w.recovery) " · 회복 걷기" else ""),
            fontSize = 11.sp, color = RunColors.Violet,
        )
    }
}

@Composable
private fun DayBody(
    item: PlanItem,
    run: RunSession?,
    today: LocalDate,
    showHeader: Boolean,
    dateText: String = "",
    walkDay: WalkDay? = null,
    onReschedule: (PlanItem) -> Unit,
) {
    val status = PlanEngine.status(item, run != null, today, run == null && walkDay?.recovery == true)
    Column(Modifier.fillMaxWidth()) {
        if (showHeader) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dateText, fontSize = 12.sp, color = RunColors.TextDim, modifier = Modifier.weight(1f))
                TypeChip(item.type)
                Spacer(Modifier.width(6.dp))
                StatusBadge(status, item.optional)
            }
            Spacer(Modifier.height(4.dp))
            Text(item.title, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }
        Text(
            "%.1fkm · 목표 페이스 %s/km".format(item.km, formatPace(PlanEngine.targetPace(item).toDouble())),
            color = RunColors.Lime, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
        )
        if (item.rescheduled) {
            Text("📅 날짜가 변경된 훈련이에요", color = RunColors.Orange, fontSize = 10.sp)
        }
        Spacer(Modifier.height(4.dp))
        item.tips.forEach {
            Text("•  $it", color = RunColors.TextDim, fontSize = 10.sp, modifier = Modifier.padding(vertical = 0.5.dp))
        }
        Spacer(Modifier.height(8.dp))

        when {
            run != null -> {
                RunMini(run)
                Spacer(Modifier.height(8.dp))
                EvalCard(PlanEngine.evaluate(item, run))
            }
            status == PlanStatus.WALKED && walkDay != null -> {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(RunColors.Violet.copy(alpha = 0.14f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("러닝 대신 회복 걷기를 했어요 🚶", color = RunColors.Violet, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(
                            "${walkDay.minutes}분 · %.1fkm".format(walkDay.km) + (walkDay.avgHr?.let { " · ♥$it" } ?: "") +
                                " — 몸 상태에 맞춘 좋은 선택이에요. 이 훈련은 다른 날로 옮길 수 있어요.",
                            color = RunColors.TextDim, fontSize = 10.sp,
                        )
                    }
                    if (!item.optional) {
                        OutlinedButton(
                            onClick = { onReschedule(item) },
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        ) { Text("날짜 변경", color = RunColors.Lime, fontSize = 12.sp) }
                    }
                }
            }
            status == PlanStatus.MISSED && !item.optional -> {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(RunColors.Pink.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("이 날 운동 기록이 없어요", color = RunColors.Pink, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("다른 날짜로 다시 지정해요.", color = RunColors.TextDim, fontSize = 10.sp)
                    }
                    Button(
                        onClick = { onReschedule(item) },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RunColors.Lime, contentColor = RunColors.Bg),
                    ) { Text("날짜 지정", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                }
            }
            status == PlanStatus.MISSED -> {
                Text("선택 훈련이라 건너뛰었어요.", color = RunColors.TextDim, fontSize = 11.sp)
            }
            else -> {
                OutlinedButton(
                    onClick = { onReschedule(item) },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) { Text("날짜 변경", color = RunColors.Lime, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
fun EvalCard(e: Evaluation) {
    val c = when {
        e.score >= 90 -> RunColors.Lime
        e.score >= 75 -> RunColors.Green
        e.score >= 60 -> RunColors.Orange
        else -> RunColors.Pink
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.copy(alpha = 0.10f))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${e.score}", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = c)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("계획 대비 평가 · ${e.grade}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(e.summary, color = RunColors.TextDim, fontSize = 10.sp, maxLines = 2)
            }
        }
        Spacer(Modifier.height(4.dp))
        e.points.forEach {
            Text("•  $it", color = RunColors.TextDim, fontSize = 10.sp, modifier = Modifier.padding(vertical = 0.5.dp))
        }
    }
}

// ───────────────────────── 리스트 ─────────────────────────

@Composable
private fun PlanListCard(
    item: PlanItem,
    run: RunSession?,
    walkDay: WalkDay?,
    today: LocalDate,
    onReschedule: (PlanItem) -> Unit,
) {
    var expanded by remember { mutableStateOf(item.date == today) }
    val status = PlanEngine.status(item, run != null, today, run == null && walkDay?.recovery == true)
    val missed = status == PlanStatus.MISSED && !item.optional

    GlassCard(Modifier.fillMaxWidth().animateContentSize(), onClick = { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(item.type.color.copy(alpha = 0.16f))
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("${item.date.dayOfMonth}", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = item.type.color)
                Text(korDow(item.date.dayOfWeek), fontSize = 9.sp, color = item.type.color)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypeChip(item.type)
                    Spacer(Modifier.width(6.dp))
                    Text("%.1fkm".format(item.km), fontSize = 10.sp, color = RunColors.TextDim)
                }
            }
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            status == PlanStatus.DONE -> RunColors.Lime
                            status == PlanStatus.WALKED -> RunColors.Violet.copy(alpha = 0.30f)
                            missed -> RunColors.Pink.copy(alpha = 0.25f)
                            else -> Color.White.copy(alpha = 0.08f)
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when {
                        status == PlanStatus.DONE -> "✓"
                        status == PlanStatus.WALKED -> "🚶"
                        missed -> "!"
                        else -> ""
                    },
                    color = if (status == PlanStatus.DONE) RunColors.Bg else RunColors.Pink,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 8.dp)) {
                DayBody(item, run, today, showHeader = false, walkDay = walkDay, onReschedule = onReschedule)
            }
        }
    }
}

// ───────────────────────── 계획 조정 이유 ─────────────────────────

private fun levelColor(level: String): Color = when (level) {
    "높음" -> RunColors.Pink
    "보통" -> RunColors.Orange
    else -> RunColors.Lime
}

/** 가장 최근에 계획을 조정한 이유를 다시 볼 수 있는 슬림 카드 */
@Composable
fun PlanNoteCard(note: PlanNote, onClick: () -> Unit) {
    val c = levelColor(note.level)
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(c.copy(alpha = 0.18f))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("피로도 ${note.score} · ${note.level}", color = c, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("최근 계획 조정 (${note.createdAt})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(
                    note.changes.firstOrNull() ?: "",
                    fontSize = 10.sp, color = RunColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text("이유 보기 ›", fontSize = 10.sp, color = RunColors.Lime)
        }
    }
}

@Composable
private fun NoteSection(title: String, lines: List<String>) {
    Spacer(Modifier.height(12.dp))
    Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
    Spacer(Modifier.height(3.dp))
    lines.forEach {
        Text(
            "•  $it", fontSize = 11.sp, color = Color.White.copy(alpha = 0.78f), lineHeight = 16.sp,
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }
}

/** '새 계획'을 눌렀을 때: 피로도, 판단 근거, 바뀐 점, 바꾼 이유(+ Claude 코치 의견)를 보여준다 */
@Composable
fun PlanNoteDialog(note: PlanNote, claudeText: String?, loading: Boolean, onShare: () -> Unit, onDismiss: () -> Unit) {
    val c = levelColor(note.level)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RunColors.Card,
        titleContentColor = Color.White,
        textContentColor = RunColors.TextDim,
        title = { Text("계획을 이렇게 조정했어요", fontSize = 17.sp) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${note.score}", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = c)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("피로도 · ${note.level}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("0~100점, 높을수록 회복이 더 필요해요", fontSize = 10.sp, color = RunColors.TextDim)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(note.score / 100f)
                            .height(6.dp)
                            .background(c)
                    )
                }
                NoteSection("판단 근거", note.facts)
                NoteSection("바뀐 계획", note.changes)
                NoteSection("왜 이렇게 바꿨나요?", note.reasons)
                if (loading) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = RunColors.Violet, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("🤖 Claude 코치가 검토하는 중...", fontSize = 11.sp, color = RunColors.TextDim)
                    }
                }
                if (claudeText != null) {
                    NoteSection("🤖 Claude 코치 의견", listOf(claudeText))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("확인", color = RunColors.Lime) } },
        dismissButton = {
            TextButton(onClick = {
                onShare()
                onDismiss()
            }) { Text("캘린더에 공유", color = RunColors.Violet) }
        },
    )
}

// ───────────────────────── 일정 변경 ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RescheduleDialog(
    item: PlanItem,
    plan: List<PlanItem>,
    today: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val suggestions = remember(item, plan, today) { PlanEngine.suggestDates(item, plan, today) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RunColors.Card,
        titleContentColor = Color.White,
        textContentColor = RunColors.TextDim,
        title = { Text("다른 날짜 지정") },
        text = {
            Column {
                Text("${item.title}을(를) 언제 할까요? 강한 훈련이 연달아 이어지지 않는 날짜를 추천해요.")
                Spacer(Modifier.height(12.dp))
                suggestions.forEach { d ->
                    OutlinedButton(onClick = { onPick(d) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${dateLabel(d, today)} 로 변경", color = RunColors.Lime)
                    }
                    Spacer(Modifier.height(6.dp))
                }
                TextButton(onClick = { showPicker = true }) { Text("직접 선택", color = Color.White) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기", color = RunColors.TextDim) } },
    )

    if (showPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val d = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return !d.isBefore(today)
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        onPick(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showPicker = false
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("취소") } },
        ) {
            DatePicker(state = state)
        }
    }
}
