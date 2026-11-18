package io.github.jihoon0501.runcoach

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate

// ─── Floating Chat FAB ─────────────────────────────────────────────────────

@Composable
fun ChatFab(onClick: () -> Unit) {
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(RunColors.Violet)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("✨", fontSize = 24.sp)
    }
}

// ─── Chat Screen ───────────────────────────────────────────────────────────

@Composable
fun ChatScreen(
    runs: List<RunSession>,
    plan: List<PlanItem>,
    today: LocalDate,
    fatigue: Int?,
    cross: List<ActivitySession> = emptyList(),
    onPlanChange: (AiCoach.PlanChange, LocalDate) -> String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val messages = remember {
        mutableStateListOf<AiCoach.ChatMessage>().apply { addAll(ChatStore.load(context)) }
    }
    var showClearDialog by remember { mutableStateOf(false) }
    fun persist() = ChatStore.save(context, messages.toList())
    var pendingChange by remember { mutableStateOf<AiCoach.PlanChange?>(null) }
    var pendingDate  by remember { mutableStateOf<LocalDate?>(null) }

    // 환영 메시지 — 대화가 비어 있을 때만 보여주고 저장하지 않는다
    val greetingMsg = remember {
        val todayPlan = plan.firstOrNull { it.date == today }
        val greeting = if (todayPlan != null)
            "안녕하세요! 오늘 계획은 '${todayPlan.title} ${todayPlan.km}km'예요. " +
            "오늘 훈련에 대해 궁금한 게 있으면 편하게 물어보세요 🏃"
        else
            "안녕하세요! 오늘 예정된 러닝 계획은 없어요. 대체 훈련이나 궁금한 점을 물어보세요 💬"
        AiCoach.ChatMessage("assistant", greeting)
    }

    // 메시지 추가 시 스크롤
    var firstScroll by remember { mutableStateOf(true) }
    LaunchedEffect(messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (firstScroll) {
            listState.scrollToItem(messages.size - 1)
            firstScroll = false
        } else {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || loading) return
        input = ""
        keyboard?.hide()
        messages += AiCoach.ChatMessage("user", text)
        persist()
        loading = true
        scope.launch {
            val apiKey = prefs.apiKey
            val reply = if (apiKey.isBlank()) {
                AiCoach.ChatReply(
                    "AI 코치를 사용하려면 Claude API 키가 필요해요.\n\n" +
                    "설정 탭 → ✨ AI 코치에서 Anthropic API 키를 입력해 주세요.\n\n" +
                    "API 키는 console.anthropic.com 에서 무료로 발급받을 수 있어요.")
            } else {
                runCatching {
                    AiCoach.chat(
                        apiKey    = apiKey,
                        userMessage = text,
                        history   = messages.dropLast(1),  // 방금 추가한 user 메시지 제외
                        runs      = runs,
                        plan      = plan,
                        today     = today,
                        fatigue   = fatigue,
                        cross     = cross,
                    )
                }.getOrElse { e ->
                    AiCoach.ChatReply("오류가 발생했어요: ${e.message}")
                }
            }
            messages += AiCoach.ChatMessage("assistant", reply.text)
            persist()
            val pc = reply.planChange
            // 오늘 이미 달렸으면 바꿀 의미가 없고, 쉬기 제안인데 오늘 훈련이 없으면 뺄 것도 없다
            val skipCard = runs.any { it.date == today } ||
                (pc != null && pc.type == null && plan.none { it.date == today })
            if (pc != null && !skipCard) {
                pendingChange = pc
                pendingDate   = today
            }
            loading = false
        }
    }

    // 빠른 질문 목록
    val quickQuestions = listOf(
        "오늘 대신 할 수 있는 운동은?",
        "오늘 가볍게 뛰고 싶어",
        "10K 기록 측정하고 싶어",
        "오늘 쉬어도 괜찮을까?",
        "인터벌 강도를 낮춰줘",
    )

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("새 대화를 시작할까요?") },
            text = { Text("지금까지의 대화 기록이 삭제돼요.") },
            confirmButton = {
                TextButton(onClick = {
                    messages.clear()
                    pendingChange = null
                    ChatStore.clear(context)
                    showClearDialog = false
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("취소") }
            },
        )
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
            Text("✨ AI 코치", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.weight(1f))
            if (messages.isNotEmpty()) {
                TextButton(onClick = { showClearDialog = true }) {
                    Text("새 대화", color = RunColors.TextDim, fontSize = 14.sp)
                }
            }
            TextButton(onClick = onClose) {
                Text("닫기", color = RunColors.TextDim, fontSize = 14.sp)
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

        // ── 메시지 목록 ──
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty()) {
                item { ChatBubble(greetingMsg) }
            }
            items(messages) { msg ->
                ChatBubble(msg)
            }

            // 빠른 질문 — 메시지가 1개(환영)일 때만 표시
            if (messages.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("빠른 질문", fontSize = 11.sp, color = RunColors.TextDim,
                            modifier = Modifier.padding(top = 4.dp))
                        quickQuestions.forEach { q ->
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(RunColors.Violet.copy(alpha = 0.18f))
                                    .clickable { input = q; send() }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(q, fontSize = 13.sp, color = RunColors.Violet)
                            }
                        }
                    }
                }
            }

            // 로딩
            if (loading) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = RunColors.Violet,
                            strokeWidth = 2.dp,
                        )
                        Text("AI가 답변 중이에요...", fontSize = 12.sp, color = RunColors.TextDim)
                    }
                }
            }
        }

        // ── 계획 변경 제안 카드 ──
        AnimatedVisibility(visible = pendingChange != null) {
            pendingChange?.let { change ->
                GlassCard(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    val hasToday = plan.any { it.date == today }
                    val cType = change.type
                    val cardTitle = when {
                        cType == null -> "📋 오늘 훈련 쉬기 제안"
                        !hasToday -> "📋 오늘 훈련 추가 제안"
                        else -> "📋 계획 변경 제안"
                    }
                    val showKm = cType == WorkoutType.EASY || cType == WorkoutType.LONG
                    val cardBody = when {
                        cType == null -> "오늘 계획에서 훈련을 뺍니다\n${change.reason}"
                        showKm -> "${cType?.label} · ${change.km}km\n${change.reason}"
                        else -> "${cType?.label}\n${change.reason}"
                    }
                    Text(cardTitle, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = Color.White)
                    Spacer(Modifier.height(4.dp))
                    Text(cardBody,
                        fontSize = 12.sp, color = RunColors.TextDim, lineHeight = 18.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val resultMsg = pendingDate?.let { onPlanChange(change, it) } ?: "계획을 바꾸지 못했어요."
                                pendingChange = null
                                messages += AiCoach.ChatMessage("assistant", resultMsg)
                                persist()
                            },
                            modifier = Modifier.weight(1f).height(36.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = RunColors.Violet),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text(
                                when {
                                    change.type == null -> "쉬기"
                                    !hasToday -> "추가하기"
                                    else -> "변경하기"
                                },
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                        OutlinedButton(
                            onClick = { pendingChange = null },
                            modifier = Modifier.weight(1f).height(36.dp),
                            contentPadding = PaddingValues(0.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp, RunColors.TextDim.copy(alpha = 0.4f)),
                        ) { Text("유지하기", fontSize = 12.sp, color = RunColors.TextDim) }
                    }
                }
            }
        }

        // ── 입력창 ──
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                placeholder = { Text("코치에게 질문하세요...", fontSize = 13.sp,
                    color = RunColors.TextDim) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                maxLines = 4,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RunColors.Violet,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = RunColors.Violet,
                ),
            )
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (input.isNotBlank() && !loading) RunColors.Violet
                                else Color.White.copy(alpha = 0.1f))
                    .clickable(enabled = input.isNotBlank() && !loading) { send() },
                contentAlignment = Alignment.Center,
            ) {
                Text("↑", fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ChatBubble(msg: AiCoach.ChatMessage) {
    val isUser = msg.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(RunColors.Violet.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center,
            ) { Text("✨", fontSize = 14.sp) }
            Spacer(Modifier.width(6.dp))
        }
        Box(
            Modifier
                .widthIn(max = 290.dp)
                .clip(RoundedCornerShape(
                    topStart = if (isUser) 16.dp else 4.dp,
                    topEnd = if (isUser) 4.dp else 16.dp,
                    bottomStart = 16.dp, bottomEnd = 16.dp,
                ))
                .background(
                    if (isUser) RunColors.Violet.copy(alpha = 0.85f)
                    else Color.White.copy(alpha = 0.08f)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(msg.text, fontSize = 13.sp, color = Color.White, lineHeight = 20.sp)
        }
    }
}

@Composable
fun CrossFab(onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(RunColors.Orange.copy(alpha = 0.9f))
            .clickable(onClick = onClick),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Text("🏋️", fontSize = 20.sp)
    }
}

/**
 * 오른쪽 아래 버튼 묶음. 손잡이(› / ‹)를 누르면 AI 코치·크로스 트레이닝 버튼을 접고 펼친다.
 * 접으면 화면 오른쪽 가장자리에 작은 손잡이만 남는다.
 */
@Composable
fun FabDock(
    hidden: Boolean,
    onToggle: () -> Unit,
    onCross: () -> Unit,
    onChat: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AnimatedVisibility(visible = !hidden) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CrossFab(onClick = onCross)
                ChatFab(onClick = onChat)
            }
        }
        // 손잡이 — 화면 가장자리에 붙도록 Scaffold가 주는 오른쪽 여백(16dp)만큼 밀어낸다
        Box(
            Modifier
                .offset(x = 16.dp)
                .width(24.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                .background(Color.White.copy(alpha = if (hidden) 0.22f else 0.14f))
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (hidden) "‹" else "›",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
