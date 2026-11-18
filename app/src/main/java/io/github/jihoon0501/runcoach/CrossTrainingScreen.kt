package io.github.jihoon0501.runcoach

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 빠르게 기록할 대표 운동. 상단에 고정해 두고 가로로 밀어서 고른다 */
private val QUICK_TYPES = listOf(
    ActivityType.WALKING, ActivityType.TREADMILL, ActivityType.CYCLING, ActivityType.INDOOR_CYCLE,
    ActivityType.HIKING, ActivityType.SWIMMING, ActivityType.SQUAT, ActivityType.LUNGE,
    ActivityType.PLANK, ActivityType.PUSH_UP, ActivityType.CORE, ActivityType.WEIGHT,
    ActivityType.STRETCHING, ActivityType.YOGA, ActivityType.PILATES, ActivityType.JUMP_ROPE,
)

// ── 크로스 트레이닝 화면: 상단 대표 운동 칩(고정) → 빠른 입력 카드 → 기록 목록 ───────────

@Composable
fun CrossTrainingScreen(
    runs: List<RunSession>,   // 이후 피로도 연결용
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    var sessions by remember { mutableStateOf(CrossTrainingStore.load(ctx)) }
    var selected by remember { mutableStateOf<ActivityType?>(null) }
    var showAll by remember { mutableStateOf(false) }

    BackHandler(enabled = showAll) { showAll = false }
    if (showAll) {
        AllTypesPicker(
            onPick = { selected = it; showAll = false },
            onClose = { showAll = false },
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(RunColors.Bg)
            .statusBarsPadding()
    ) {
        // ── 헤더 ──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🏋️ 크로스 트레이닝", fontSize = 18.sp,
                fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) {
                Text("닫기", color = RunColors.TextDim, fontSize = 14.sp)
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

        // ── 대표 운동 칩 (고정, 가로 스크롤) ──
        QuickChipsRow(
            selected = selected,
            onSelect = { selected = if (selected == it) null else it },
            onMore = { showAll = true },
        )

        // ── 빠른 입력 카드 ──
        selected?.let { t ->
            QuickEntryCard(
                type = t,
                onSave = { s ->
                    CrossTrainingStore.save(ctx, s)
                    sessions = CrossTrainingStore.load(ctx)
                    selected = null
                    Toast.makeText(ctx, "${s.type.label} ${s.durationMin}분을 기록했어요", Toast.LENGTH_SHORT).show()
                },
                onCancel = { selected = null },
            )
        }

        // ── 기록 목록 ──
        val weekAgo = remember { LocalDate.now().minusDays(6) }
        val week = sessions.filter { it.date >= weekAgo }
        if (week.isNotEmpty()) {
            Text(
                "최근 7일 ${week.size}회 · ${week.sumOf { it.durationMin }}분",
                color = RunColors.TextDim, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        if (sessions.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🏋️", fontSize = 40.sp)
                    Text("기록된 운동이 없어요", color = RunColors.TextDim, fontSize = 14.sp)
                    Text("위에서 운동을 눌러 바로 기록해 보세요",
                        color = RunColors.TextDim.copy(alpha = 0.6f), fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sessions) { s ->
                    CrossSessionCard(s, PlanEngine.crossKm(s, runs), onDelete = {
                        CrossTrainingStore.delete(ctx, s.start.toEpochMilli())
                        sessions = CrossTrainingStore.load(ctx)
                    })
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

// ── 상단 대표 운동 칩 ─────────────────────────────────────────────

@Composable
private fun QuickChipsRow(
    selected: ActivityType?,
    onSelect: (ActivityType) -> Unit,
    onMore: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QUICK_TYPES.forEach { t ->
            ActivityTypeChip(
                type = t,
                selected = selected == t,
                onClick = { onSelect(t) },
                modifier = Modifier.width(64.dp),
            )
        }
        // 전체 보기
        Box(
            Modifier
                .width(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(RunColors.Violet.copy(alpha = 0.18f))
                .clickable(onClick = onMore)
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("＋", fontSize = 18.sp, color = RunColors.Violet, fontWeight = FontWeight.Bold)
                Text("전체", fontSize = 10.sp, color = RunColors.Violet, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ActivityTypeChip(
    type: ActivityType,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) RunColors.Violet.copy(alpha = 0.85f)
                else Color.White.copy(alpha = 0.07f)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(type.emoji, fontSize = 18.sp)
            Text(
                type.label, fontSize = 10.sp, color = Color.White, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

// ── 빠른 입력 카드 ───────────────────────────────────────────────

@Composable
private fun QuickEntryCard(
    type: ActivityType,
    onSave: (ActivitySession) -> Unit,
    onCancel: () -> Unit,
) {
    var minutes by remember(type) { mutableStateOf(type.defaultMin.toString()) }
    var distance by remember(type) { mutableStateOf("") }
    var sets by remember(type) { mutableStateOf("3") }
    var reps by remember(type) { mutableStateOf("10") }
    var calories by remember(type) { mutableStateOf("") }
    var avgHr by remember(type) { mutableStateOf("") }
    var note by remember(type) { mutableStateOf("") }
    var yesterday by remember(type) { mutableStateOf(false) }
    var more by remember(type) { mutableStateOf(false) }

    GlassCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${type.emoji} ${type.label}", color = Color.White,
                fontWeight = FontWeight.Bold, fontSize = 15.sp,
                modifier = Modifier.weight(1f))
            DayChip("오늘", !yesterday) { yesterday = false }
            Spacer(Modifier.width(6.dp))
            DayChip("어제", yesterday) { yesterday = true }
            TextButton(onClick = onCancel) {
                Text("닫기", color = RunColors.TextDim, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CrossInputField("시간(분)", minutes, { minutes = onlyNumber(it, false) }, Modifier.weight(1f))
            if (type.hasDistance) {
                CrossInputField("거리(km)", distance, { distance = onlyNumber(it, true) },
                    Modifier.weight(1f), decimal = true)
            }
            if (type.hasReps) {
                CrossInputField("세트", sets, { sets = onlyNumber(it, false) }, Modifier.weight(1f))
                CrossInputField("횟수", reps, { reps = onlyNumber(it, false) }, Modifier.weight(1f))
            }
        }
        TextButton(onClick = { more = !more }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(if (more) "접기 ▴" else "더 입력 (칼로리·심박·메모) ▾",
                color = RunColors.TextDim, fontSize = 12.sp)
        }
        if (more) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CrossInputField("칼로리(kcal)", calories, { calories = onlyNumber(it, false) }, Modifier.weight(1f))
                CrossInputField("평균 심박", avgHr, { avgHr = onlyNumber(it, false) }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = note, onValueChange = { note = it },
                label = { Text("메모 (선택)", fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
                shape = RoundedCornerShape(10.dp),
                colors = crossFieldColors(),
            )
            Spacer(Modifier.height(6.dp))
        }
        val min = minutes.toLongOrNull() ?: 0L
        Button(
            onClick = {
                val start = Instant.now().minusSeconds(if (yesterday) 86_400L else 0L)
                onSave(
                    ActivitySession(
                        type        = type,
                        start       = start,
                        durationSec = min * 60L,
                        distanceKm  = if (type.hasDistance) distance.toDoubleOrNull() ?: 0.0 else 0.0,
                        calories    = calories.toIntOrNull(),
                        avgHr       = avgHr.toIntOrNull(),
                        sets        = if (type.hasReps) sets.toIntOrNull() ?: 0 else 0,
                        reps        = if (type.hasReps) reps.toIntOrNull() ?: 0 else 0,
                        note        = note.trim(),
                        source      = "manual",
                    )
                )
            },
            enabled = min > 0L,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RunColors.Violet),
            shape = RoundedCornerShape(12.dp),
        ) { Text("저장", fontWeight = FontWeight.Bold, fontSize = 15.sp) }
    }
}

@Composable
private fun DayChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) RunColors.Violet.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(label, color = Color.White, fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

// ── 전체 운동 종류 선택 ──────────────────────────────────────────

@Composable
private fun AllTypesPicker(
    onPick: (ActivityType) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(RunColors.Bg)
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("운동 종류 전체", fontSize = 18.sp,
                fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) {
                Text("닫기", color = RunColors.TextDim, fontSize = 14.sp)
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CrossCategory.entries.forEach { cat ->
                val types = ActivityType.entries.filter { it.category == cat }
                if (types.isNotEmpty()) {
                    Text(cat.label, color = categoryColor(cat), fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    types.chunked(4).forEach { rowTypes ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowTypes.forEach { t ->
                                ActivityTypeChip(
                                    type = t, selected = false, onClick = { onPick(t) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(4 - rowTypes.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

// ── 기록 카드 ────────────────────────────────────────────────────

@Composable
private fun CrossSessionCard(s: ActivitySession, kmEq: Double, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(RunColors.Violet.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) { Text(s.type.emoji, fontSize = 20.sp) }

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.type.label, color = Color.White,
                        fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(categoryColor(s.type.category).copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(s.type.category.label, fontSize = 10.sp,
                            color = categoryColor(s.type.category))
                    }
                }
                Text(
                    buildString {
                        append(s.date.format(DateTimeFormatter.ofPattern("M/d(E)")))
                        append("  ${s.durationMin}분")
                        if (s.distanceKm > 0) append("  ${"%.1f".format(s.distanceKm)}km")
                        if (s.sets > 0) append("  ${s.sets}세트×${s.reps}회")
                        s.avgHr?.let { append("  ♥$it") }
                        if (kmEq >= 0.05) append("  · 러닝 환산 +${"%.1f".format(kmEq)}km")
                    },
                    color = RunColors.TextDim, fontSize = 12.sp,
                )
            }
            s.calories?.let {
                Text("${it}kcal", color = RunColors.Orange,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (expanded) {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Spacer(Modifier.height(8.dp))
            if (s.note.isNotBlank()) {
                Text(s.note, color = RunColors.TextDim, fontSize = 12.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDelete) {
                    Text("삭제", color = RunColors.Pink, fontSize = 12.sp)
                }
            }
        }
    }
}

// ── 공통 ─────────────────────────────────────────────────────────

@Composable
private fun CrossInputField(
    label: String, value: String, onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier, decimal: Boolean = false,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.sp, maxLines = 1) },
        modifier = modifier,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        shape = RoundedCornerShape(10.dp),
        colors = crossFieldColors(),
    )
}

@Composable
private fun crossFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = RunColors.Violet,
    unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedLabelColor = RunColors.TextDim,
    unfocusedLabelColor = RunColors.TextDim,
    cursorColor = RunColors.Violet,
)

/** 숫자만 남긴다 (소수점은 한 번만 허용) */
private fun onlyNumber(s: String, allowDot: Boolean): String {
    val sb = StringBuilder()
    var dot = false
    for (c in s) {
        if (c.isDigit()) sb.append(c)
        else if (allowDot && c == '.' && !dot) { sb.append(c); dot = true }
    }
    return sb.toString()
}

private fun categoryColor(cat: CrossCategory) = when (cat) {
    CrossCategory.CARDIO   -> RunColors.Blue
    CrossCategory.STRENGTH -> RunColors.Orange
    CrossCategory.SPORT    -> RunColors.Pink
    CrossCategory.RECOVERY -> RunColors.Green
    CrossCategory.OTHER    -> RunColors.TextDim
}
