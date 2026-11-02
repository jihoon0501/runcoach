package io.github.jihoon0501.runcoach

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RunLogAppTheme { RunLogApp() }
        }
    }
}

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("홈", Icons.Default.Home),
    RECORDS("기록", Icons.AutoMirrored.Filled.List),
    PLAN("계획", Icons.Default.DateRange),
    SETTINGS("설정", Icons.Default.Settings),
}

/** 첫 실행이면 안내 화면(내 정보 입력, 권한·연동 허용)을 먼저 보여주고, 끝나면 메인 앱으로 넘어간다 */
@Composable
fun RunLogApp() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context.applicationContext) }
    val samsung = remember { SamsungRepo(context.applicationContext) }
    val initial = remember { prefs.loadProfile().also { UserProfile.data = it } }
    var onboarded by remember { mutableStateOf(initial.onboarded) }
    if (!onboarded) {
        OnboardingScreen(prefs, samsung) { onboarded = true }
    } else {
        MainApp()
    }
}

@Composable
fun MainApp() {
    val context = LocalContext.current
    val samsung = remember { SamsungRepo(context.applicationContext) }
    val prefs = remember { Prefs(context.applicationContext) }
    val store = remember { RunStore(context.applicationContext, samsung) }
    val healthDaily = remember { HealthDaily(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }

    var runs by remember { mutableStateOf<List<RunSession>>(emptyList()) }
    var plan by remember { mutableStateOf(PlanEngine.dedupeIds(prefs.loadPlan() ?: emptyList())) }
    var state by remember { mutableStateOf(SyncState.Idle) }
    var errorMsg by remember { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var showToday by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var profileVersion by remember { mutableStateOf(0) }
    var health by remember { mutableStateOf<List<DayHealth>>(emptyList()) }
    var walks by remember { mutableStateOf<List<WalkSession>>(emptyList()) }
    var showReadiness by remember { mutableStateOf(false) }
    var planNote by remember { mutableStateOf(prefs.loadPlanNote()) }
    var showNote by remember { mutableStateOf(false) }
    var claudeNote by remember { mutableStateOf<String?>(null) }
    var noteLoading by remember { mutableStateOf(false) }
    var lastSyncText by remember { mutableStateOf("") }
    val aiResults = remember { mutableStateMapOf<String, AiResult>().apply { putAll(prefs.loadAi()) } }
    val aiLoading = remember { mutableStateListOf<String>() }

    fun currentReadiness(runsNow: List<RunSession>): Readiness? =
        ReadinessEngine.compute(
            health, PlanEngine.fatigueScore(runsNow, today, walks), plan.firstOrNull { it.date == today }, today,
            PlanEngine.walkSummary(walks, today),
        )
    val readiness = remember(health, runs, walks, plan) { currentReadiness(runs) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** 계획을 구글 캘린더에 반영하고 결과를 토스트로 알린다 */
    fun runCalendarSync(list: List<PlanItem>) {
        scope.launch {
            val res = try {
                withContext(Dispatchers.IO) { CalendarSync.sync(context, list, today, prefs) }
            } catch (e: Throwable) {
                Toast.makeText(context, "캘린더 공유 실패: ${e.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (res == null) {
                Toast.makeText(context, "폰에서 구글 계정 캘린더를 찾지 못했어요. 구글 캘린더 앱 로그인과 동기화를 확인해 주세요.", Toast.LENGTH_LONG).show()
            } else {
                val extra = if (res.replacedOld > 0) " · 이전 일정 ${res.replacedOld}개 정리" else ""
                Toast.makeText(
                    context,
                    "${res.calendar}에 반영했어요: 추가 ${res.added} · 수정 ${res.updated} · 삭제 ${res.removed}$extra",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    var pendingShare by remember { mutableStateOf<List<PlanItem>?>(null) }
    val calPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val list = pendingShare
        pendingShare = null
        if (granted.values.all { it } && list != null) {
            runCalendarSync(list)
        } else {
            Toast.makeText(context, "캘린더 권한이 필요해요", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareToCalendar(list: List<PlanItem>) {
        if (CalendarSync.hasPermission(context)) {
            runCalendarSync(list)
        } else {
            pendingShare = list
            calPermLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
        }
    }

    /** 훈련 1주차가 시작된 월요일 (아직 정해지지 않았으면 null) */
    fun trainingAnchor(): LocalDate? =
        prefs.trainingStart.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** 목표 기록을 입력하지 않았다면, 최근 기록으로 기준 페이스를 정한다 */
    fun refreshAutoGoal(list: List<RunSession>) {
        if (UserProfile.data.goalTimeSec <= 0) UserProfile.autoGoalPace = PlanEngine.autoGoalPace(list, today)
    }

    fun savePlan(newPlan: List<PlanItem>) {
        plan = newPlan
        prefs.savePlan(newPlan)
    }

    fun fail(e: Throwable) {
        errorMsg = "${e::class.java.simpleName}: ${e.message}"
        state = SyncState.Error
    }

    /**
     * 삼성 헬스와 동기화.
     * 저장된 기록을 먼저 보여주고, 새로 생긴 기록만 가져와 합친다. [full]이면 저장본을 지우고 전체를 다시 읽는다.
     */
    fun sync(full: Boolean = false) {
        if (syncing) return
        scope.launch {
            syncing = true
            try {
                if (full) store.clear()
                if (runs.isEmpty()) {
                    val cached = store.loadCached()
                    if (cached.isNotEmpty()) {
                        runs = cached
                        state = SyncState.Ready
                    }
                }
                if (runs.isEmpty()) state = SyncState.Loading
                val loaded = store.sync { partial ->
                    runs = partial
                    state = SyncState.Ready
                }
                runs = loaded
                refreshAutoGoal(loaded)
                walks = store.walks
                state = SyncState.Ready
                lastSyncText = store.lastSyncText()
                // 건강 데이터(수면·심박·에너지 점수)도 새로 생긴 것만 가져온다. 실패해도 러닝 기능은 그대로 쓴다
                try {
                    if (healthDaily.hasPermission()) health = healthDaily.sync()
                } catch (e: Throwable) {
                    // 무시
                }
                // 앞으로 남은 계획이 없으면 최근 기록으로 새 한 달 계획을 만든다
                if (plan.none { it.date >= today }) {
                    val upd = PlanEngine.replan(loaded, plan, today, currentReadiness(loaded), walks, trainingAnchor())
                    if (prefs.trainingStart.isBlank()) prefs.trainingStart = upd.anchor.toString()
                    savePlan(upd.newPlan)
                    planNote = upd.note
                    prefs.savePlanNote(upd.note)
                }
            } catch (e: Throwable) {
                if (runs.isEmpty()) {
                    fail(e)
                } else {
                    Toast.makeText(context, "동기화 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                syncing = false
            }
        }
    }

    fun requestAi(r: RunSession) {
        val key = r.start.toEpochMilli().toString()
        if (key in aiLoading) return
        scope.launch {
            aiLoading.add(key)
            try {
                val matches = PlanEngine.match(plan, runs)
                val item = plan.firstOrNull { matches[it.id]?.start == r.start }
                val eval = item?.let { PlanEngine.evaluate(it, r) }
                val history = runs.filter { it.start < r.start }
                val upTo = runs.filter { it.start <= r.start }
                val recovery = PlanEngine.recoveryAdvice(upTo, r.date)
                val local = AiCoach.local(r, history, item, eval, recovery)
                val apiKey = prefs.apiKey
                val res = if (apiKey.isNotBlank()) {
                    try {
                        AiResult(AiCoach.claude(apiKey, r, history, item, eval, PlanEngine.adviceText(upTo, r.date)), "Claude")
                    } catch (e: Throwable) {
                        AiResult(local + "\n\n(Claude 호출 실패: ${e.message})", "기기 내 코치")
                    }
                } else {
                    AiResult(local, "기기 내 코치")
                }
                aiResults[key] = res
                prefs.saveAi(key, res)
            } finally {
                aiLoading.remove(key)
            }
        }
    }

    val onConnect: () -> Unit = {
        scope.launch {
            try {
                if (samsung.requestPermission(context as Activity)) sync() else state = SyncState.NeedPermission
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }
    val onRefresh: () -> Unit = { sync() }
    val onReschedule: (PlanItem, LocalDate) -> Unit = { item, d ->
        val moved = plan.map { if (it.id == item.id) it.copy(date = d, rescheduled = true) else it }.sortedBy { it.date }
        val newPlan = PlanEngine.relabelWeeks(moved, trainingAnchor() ?: PlanEngine.mondayOf(moved.minOf { it.date }))
        savePlan(newPlan)
        if (prefs.calendarAuto) shareToCalendar(newPlan)
    }
    /** 최근 기록과 피로도를 반영해 계획을 새로 만들고, 왜 바꿨는지 설명창을 띄운다 */
    val onRegenerate: () -> Unit = {
        val upd = PlanEngine.replan(runs, plan, today, readiness, walks, trainingAnchor())
        if (prefs.trainingStart.isBlank()) prefs.trainingStart = upd.anchor.toString()
        savePlan(upd.newPlan)
        planNote = upd.note
        prefs.savePlanNote(upd.note)
        claudeNote = null
        showNote = true
        if (prefs.calendarAuto) shareToCalendar(upd.newPlan)
        val key = prefs.apiKey
        if (key.isNotBlank()) {
            scope.launch {
                noteLoading = true
                claudeNote = try {
                    AiCoach.claudePlanNote(key, upd.note)
                } catch (e: Throwable) {
                    "Claude 의견을 가져오지 못했어요: ${e.message}"
                }
                noteLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        // 이전 버전에서 쓰던 계획이 있으면, 그 계획의 첫 주를 '훈련 1주차'로 정하고 모든 훈련의 주차를 한 기준으로 맞춘다
        if (prefs.trainingStart.isBlank() && plan.isNotEmpty()) {
            prefs.trainingStart = PlanEngine.mondayOf(plan.minOf { it.date }).toString()
        }
        trainingAnchor()?.let { a ->
            val relabeled = PlanEngine.relabelWeeks(plan, a)
            if (relabeled != plan) savePlan(relabeled)
        }
        // 첫 실행이거나, 저장된 계획에서 겹친 id를 정리했다면 다시 저장 (알림도 이 계획을 읽는다)
        val savedPlan = prefs.loadPlan()
        if (savedPlan == null || savedPlan.map { it.id }.toSet() != plan.map { it.id }.toSet()) prefs.savePlan(plan)
        Reminder.schedule(context)
        if (Build.VERSION.SDK_INT >= 33 && prefs.reminderOn &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        health = healthDaily.loadCached()
        val cached = store.loadCached()
        walks = store.walks
        if (cached.isNotEmpty()) {
            runs = cached
            refreshAutoGoal(cached)
            state = SyncState.Ready
            lastSyncText = store.lastSyncText()
        }
        try {
            if (samsung.hasPermission()) sync() else if (cached.isEmpty()) state = SyncState.NeedPermission
        } catch (e: Throwable) {
            if (cached.isEmpty()) fail(e)
        }
    }

    // 오늘의 러닝 팝업 (하루 1번, 아직 안 했을 때)
    val matches = remember(plan, runs) { PlanEngine.match(plan, runs) }
    val todayItem = plan.firstOrNull { it.date == today }
    val todayDone = todayItem != null && matches.containsKey(todayItem.id)
    LaunchedEffect(state, plan) {
        if (state != SyncState.Idle && state != SyncState.Loading &&
            todayItem != null && !todayDone && prefs.lastPopupDate != today.toString()
        ) {
            showToday = true
            prefs.lastPopupDate = today.toString()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(RunColors.BgTop, RunColors.Bg)))
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            bottomBar = { CompactNavBar(tab) { tab = it } },
        ) { pad ->
            key(profileVersion) {
            Crossfade(targetState = tab, modifier = Modifier.padding(pad), label = "tab") { t ->
                when (t) {
                    Tab.HOME -> HomeScreen(runs, today, state, errorMsg, plan, onConnect, onRefresh, syncing, readiness) { showReadiness = true }
                    Tab.RECORDS -> RecordsScreen(runs, today, aiResults, aiLoading) { r -> requestAi(r) }
                    Tab.PLAN -> PlanScreen(
                        plan, runs, today, onReschedule, onRegenerate,
                        note = planNote,
                        onShowNote = { showNote = true },
                        onShareCalendar = { shareToCalendar(plan) },
                        walks = walks,
                    )
                    Tab.SETTINGS -> SettingsScreen(
                        state = state,
                        runs = runs,
                        diagnosticJson = { runsDiagnosticJson(runs) },
                        onConnect = onConnect,
                        onRefresh = onRefresh,
                        lastSyncText = lastSyncText,
                        onFullReload = { sync(true) },
                        onShareCalendar = { shareToCalendar(plan) },
                        onProfileSaved = {
                            trainingAnchor()?.let { a -> savePlan(PlanEngine.relabelWeeks(plan, a)) }
                            profileVersion++
                        },
                    )
                }
            }
            }
        }
    }

    val readinessToShow = readiness
    if (showReadiness && readinessToShow != null) {
        ReadinessDialog(readinessToShow) { showReadiness = false }
    }

    val noteToShow = planNote
    if (showNote && noteToShow != null) {
        PlanNoteDialog(noteToShow, claudeNote, noteLoading, onShare = { shareToCalendar(plan) }) { showNote = false }
    }

    if (showToday && todayItem != null) {
        AlertDialog(
            onDismissRequest = { showToday = false },
            containerColor = RunColors.Card,
            titleContentColor = Color.White,
            textContentColor = RunColors.TextDim,
            title = { Text("오늘의 러닝 🏃") },
            text = {
                Column {
                    Text("${todayItem.type.emoji} ${todayItem.title}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "%.1fkm · 목표 %s/km".format(todayItem.km, formatPace(PlanEngine.targetPace(todayItem).toDouble())),
                        color = RunColors.Lime, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(10.dp))
                    todayItem.tips.take(3).forEach { Text("•  $it", fontSize = 13.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showToday = false; tab = Tab.PLAN }) { Text("계획 보기", color = RunColors.Lime) }
            },
            dismissButton = {
                TextButton(onClick = { showToday = false }) { Text("닫기", color = RunColors.TextDim) }
            },
        )
    }
}

/** 높이를 줄인 하단 탭 바 (아이콘 20dp + 10sp 라벨) */
@Composable
fun CompactNavBar(tab: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(RunColors.Card.copy(alpha = 0.96f))
            .navigationBarsPadding()
            .height(50.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.values().forEach { t ->
            val sel = tab == t
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (sel) RunColors.Lime else Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 3.dp)
                ) {
                    Icon(
                        t.icon, contentDescription = t.label,
                        tint = if (sel) RunColors.Bg else RunColors.TextDim,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(t.label, fontSize = 10.sp, color = if (sel) RunColors.Lime else RunColors.TextDim)
            }
        }
    }
}
