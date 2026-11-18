package io.github.jihoon0501.runcoach

import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import android.Manifest
import android.app.TimePickerDialog
import android.os.Build
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 오늘의 러닝 알림 설정 */
@Composable
fun ReminderCard() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var on by remember { mutableStateOf(prefs.reminderOn) }
    var hour by remember { mutableStateOf(prefs.reminderHour) }
    var minute by remember { mutableStateOf(prefs.reminderMinute) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(context, "알림 권한이 꺼져 있어서 알림이 보이지 않아요", Toast.LENGTH_LONG).show()
    }

    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionTitle("오늘의 러닝 알림")
                Text(
                    "매일 %02d:%02d에 오늘 할 훈련을 알려줘요".format(hour, minute),
                    color = RunColors.TextDim, fontSize = 11.sp,
                )
            }
            Switch(
                checked = on,
                onCheckedChange = { v ->
                    on = v
                    prefs.reminderOn = v
                    if (v && Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    Reminder.schedule(context)
                },
                colors = SwitchDefaults.colors(checkedTrackColor = RunColors.Lime, checkedThumbColor = RunColors.Bg),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                TimePickerDialog(context, { _, h, m ->
                    hour = h
                    minute = m
                    prefs.reminderHour = h
                    prefs.reminderMinute = m
                    Reminder.schedule(context)
                }, hour, minute, true).show()
            }) { Text("시간 변경 %02d:%02d".format(hour, minute), color = RunColors.Lime) }

            OutlinedButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                val shown = Reminder.notifyToday(context, force = true)
                if (!shown) Toast.makeText(context, "알림 권한을 허용한 뒤 다시 눌러주세요", Toast.LENGTH_SHORT).show()
            }) { Text("알림 테스트", color = RunColors.Lime) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "배터리 절약 모드에서는 알림이 몇 분 늦을 수 있어요.",
            color = RunColors.TextDim, fontSize = 11.sp,
        )
    }
}

/** AI 평가용 Claude API 키 설정 */
@Composable
fun AiSettingsCard() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var key by remember { mutableStateOf(prefs.apiKey) }

    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("AI 평가 (Claude)")
        Spacer(Modifier.height(6.dp))
        Text(
            if (key.isBlank()) "키가 없으면 기기 안에서 계산하는 기본 코치가 평가해요."
            else "Claude가 러닝 기록을 분석해서 평가해요.",
            color = RunColors.TextDim, fontSize = 11.sp,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = key,
            onValueChange = {
                key = it
                prefs.apiKey = it
            },
            label = { Text("Anthropic API 키") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = RunColors.Lime,
                unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                focusedLabelColor = RunColors.Lime,
                unfocusedLabelColor = RunColors.TextDim,
                cursorColor = RunColors.Lime,
            ),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "키를 입력하면 평가할 때마다 해당 러닝 기록과 최근 이력, 나이·키·체중·목표 같은 기본 정보가 Anthropic 서버로 전송돼요. " +
                "키는 이 폰의 앱 저장소에만 저장되고, 사용량만큼 요금이 부과돼요.",
            color = RunColors.TextDim, fontSize = 11.sp,
        )
    }
}

/** 구글 캘린더 공유 설정: 캘린더 선택, 새 계획 자동 반영, 일정 시작 시각, 지금 공유 */
@Composable
fun CalendarCard(onShare: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var auto by remember { mutableStateOf(prefs.calendarAuto) }
    var hour by remember { mutableStateOf(prefs.calendarHour) }
    var minute by remember { mutableStateOf(prefs.calendarMinute) }
    var calendars by remember { mutableStateOf<List<CalendarInfo>>(emptyList()) }
    var selectedId by remember { mutableStateOf(prefs.calendarId) }
    var showPicker by remember { mutableStateOf(false) }

    fun refresh() {
        if (CalendarSync.hasPermission(context)) {
            calendars = CalendarSync.listCalendars(context)
            if (calendars.none { it.id == selectedId }) selectedId = calendars.firstOrNull()?.id ?: 0L
        }
    }
    LaunchedEffect(Unit) { refresh() }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { g ->
        if (g.values.all { it }) {
            refresh()
            showPicker = true
        } else {
            Toast.makeText(context, "캘린더 권한이 필요해요", Toast.LENGTH_SHORT).show()
        }
    }

    val current = calendars.firstOrNull { it.id == selectedId }

    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("구글 캘린더 공유")
        Spacer(Modifier.height(3.dp))
        Text(
            "훈련 계획을 구글 계정 캘린더에 일정으로 넣어요. 계획이 바뀌면 같은 일정이 수정되거나 삭제돼요.",
            color = RunColors.TextDim, fontSize = 10.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("공유할 캘린더", fontSize = 10.sp, color = RunColors.TextDim)
                Text(
                    current?.let { "${it.name} (${it.account})" } ?: "아직 선택 안 함",
                    fontSize = 11.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(
                onClick = {
                    if (CalendarSync.hasPermission(context)) {
                        refresh()
                        showPicker = true
                    } else {
                        permLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                    }
                },
                modifier = Modifier.height(32.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) { Text("선택", color = RunColors.Lime, fontSize = 12.sp) }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("새 계획을 만들면 자동으로 반영", fontSize = 11.sp, color = Color.White)
                Text("끄면 '지금 공유'를 눌렀을 때만 반영해요", fontSize = 10.sp, color = RunColors.TextDim)
            }
            Switch(
                checked = auto,
                onCheckedChange = { v -> auto = v; prefs.calendarAuto = v },
                colors = SwitchDefaults.colors(checkedTrackColor = RunColors.Lime, checkedThumbColor = RunColors.Bg),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    TimePickerDialog(context, { _, h, m ->
                        hour = h; minute = m
                        prefs.calendarHour = h; prefs.calendarMinute = m
                    }, hour, minute, true).show()
                },
                modifier = Modifier.height(34.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) { Text("시작 %02d:%02d".format(hour, minute), color = RunColors.Lime, fontSize = 12.sp) }
            Button(
                onClick = onShare,
                modifier = Modifier.height(34.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RunColors.Lime, contentColor = RunColors.Bg),
            ) { Text("지금 공유", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            containerColor = RunColors.Card,
            titleContentColor = Color.White,
            textContentColor = RunColors.TextDim,
            title = { Text("공유할 캘린더", fontSize = 16.sp) },
            text = {
                Column {
                    if (calendars.isEmpty()) {
                        Text("폰에 구글 계정 캘린더가 보이지 않아요. 구글 캘린더 앱에 로그인되어 있는지 확인해 주세요.", fontSize = 12.sp)
                    }
                    calendars.forEach { c ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { selectedId = c.id; prefs.calendarId = c.id }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = c.id == selectedId,
                                onClick = { selectedId = c.id; prefs.calendarId = c.id },
                                colors = RadioButtonDefaults.colors(selectedColor = RunColors.Lime, unselectedColor = RunColors.TextDim),
                            )
                            Spacer(Modifier.width(4.dp))
                            Column {
                                Text(c.name + if (c.isPrimary) " · 기본" else "", fontSize = 13.sp, color = Color.White)
                                Text(c.account, fontSize = 10.sp, color = RunColors.TextDim)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("확인", color = RunColors.Lime) } },
        )
    }
}

/** 설정: 내 정보 요약 + 수정, 그리고 이 폰에 저장된 내 데이터 모두 지우기 */
/** 신체 탭의 설정 카드: 체중계·인바디 앱이 삼성 헬스/Health Connect에 넣은 키·체중·체성분을 가져오고, 내 정보에 반영한다 */
@Composable
fun BodyInfoCard(onProfileSaved: () -> Unit, onDataChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val useHc = remember { prefs.dataSource == DataSourceKind.HEALTH_CONNECT }
    val store = remember { BodyStore(context.applicationContext, prefs.dataSource) }
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(store.load()) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(prefs.bodyAutoApply) }
    var inAi by remember { mutableStateOf(prefs.bodyInAi) }

    fun refresh(full: Boolean = false) {
        scope.launch {
            busy = true
            msg = try {
                list = store.refresh(full)
                onDataChanged()
                BodyStore.updateAiContext(prefs, list)
                if (BodyStore.applyToProfile(prefs, list)) onProfileSaved()
                if (list.isEmpty()) "읽을 신체 기록이 없어요. 체중계·인바디 앱이 삼성 헬스(또는 Health Connect)로 보내도록 설정했는지 확인해 주세요."
                else "가져왔어요 ✅"
            } catch (e: Throwable) {
                "읽지 못했어요: ${e::class.java.simpleName}"
            }
            busy = false
        }
    }

    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.contains(HealthPermission.getReadPermission(WeightRecord::class))) refresh() else msg = "허용하지 않았어요"
    }
    val onFetch: () -> Unit = {
        if (useHc) {
            if (HealthConnectRepo.isAvailable(context)) hcLauncher.launch(HealthConnectRepo.BODY_PERMISSIONS)
            else msg = "Health Connect를 설치하거나 업데이트해 주세요"
        } else {
            scope.launch {
                try {
                    if (store.hasPermission() || store.request(context as android.app.Activity)) refresh() else msg = "허용하지 않았어요"
                } catch (e: Throwable) {
                    msg = "연결하지 못했어요: ${e::class.java.simpleName}"
                }
            }
        }
    }

    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("신체·체성분 (선택)")
        Spacer(Modifier.height(4.dp))
        val latest = BodyStore.latest(list)
        if (latest == null) {
            Text(
                "체중계·인바디 앱이 삼성 헬스(또는 Health Connect)에 넣은 키·체중·체지방을 읽어요. 읽기만 하고, 이 폰 안에서만 써요. " +
                    "가져오면 체중 변화 이력을 최대 5년치 이 폰에 쌓고, 내 정보의 키·체중도 새 측정값으로 자동 갱신해요. 혈압 같은 의료성 정보는 읽지 않아요.",
                color = RunColors.TextDim, fontSize = 11.sp, lineHeight = 16.sp,
            )
        } else {
            Text(
                listOfNotNull(
                    latest.weightKg?.let { "체중 %.1fkg".format(it) },
                    latest.heightCm?.let { "키 %.0fcm".format(it) },
                    latest.bmi?.let { "BMI %.1f".format(it) },
                    latest.bodyFatPct?.let { "체지방 %.1f%%".format(it) },
                    latest.skeletalMuscleKg?.let { "골격근 %.1fkg".format(it) },
                ).joinToString(" · "),
                color = Color.White, fontSize = 12.sp,
            )
            Text("측정일 ${latest.date}", color = RunColors.TextDim, fontSize = 10.sp)
            BodyStore.weightChange(list)?.let { (diff, days) ->
                Text(
                    "${days}일간 체중 변화 ${if (diff >= 0) "+" else ""}%.1fkg".format(diff),
                    color = RunColors.TextDim, fontSize = 10.sp,
                )
            }
            val p = prefs.loadProfile()
            val w = latest.weightKg?.let { Math.round(it) }
            val h = latest.heightCm?.let { Math.round(it) }
            val differs = (w != null && w != p.weightKg) || (h != null && h != p.heightCm)
            if (differs && !auto) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "내 정보: 체중 ${p.weightKg}kg · 키 ${p.heightCm}cm — 최근 기록과 달라요. 반영하면 소모 열량 추정 등에 새 값을 써요.",
                    color = RunColors.TextDim, fontSize = 10.sp, lineHeight = 15.sp,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = {
                        val np = p.copy(weightKg = w ?: p.weightKg, heightCm = h ?: p.heightCm)
                        prefs.saveProfile(np)
                        UserProfile.data = np
                        onProfileSaved()
                    },
                    modifier = Modifier.height(32.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                ) { Text("내 정보에 반영", color = RunColors.Lime, fontSize = 12.sp) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "새 측정값을 내 정보(키·체중)에 자동 반영",
                color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f),
            )
            Switch(checked = auto, onCheckedChange = { auto = it; prefs.bodyAutoApply = it })
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "AI 평가에 체중 변화량 포함 (선택)",
                color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f),
            )
            Switch(checked = inAi, onCheckedChange = {
                inAi = it
                prefs.bodyInAi = it
                BodyStore.updateAiContext(prefs, list)
            })
        }
        Text(
            "켜면 AI 평가를 요청할 때 '최근 8주 체중 변화 -2.1kg' 같은 변화량만 함께 보내요. 체지방·BMI와 체중의 절대값은 따로 보내지 않아요 " +
                "(키·체중은 기본 정보로 이미 전송돼요). Anthropic API 키를 입력한 경우에만 쓰여요.",
            color = RunColors.TextDim, fontSize = 10.sp, lineHeight = 15.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onFetch,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = RunColors.Lime, contentColor = RunColors.Bg),
                modifier = Modifier.height(36.dp),
            ) { Text(if (busy) "읽는 중..." else if (list.isEmpty()) "신체 정보 가져오기" else "다시 가져오기", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            if (list.isNotEmpty() && !useHc) {
                OutlinedButton(
                    onClick = { refresh(full = true) },
                    enabled = !busy,
                    modifier = Modifier.height(36.dp),
                ) { Text("이력 전체 다시 읽기", color = RunColors.Lime, fontSize = 12.sp) }
            }
        }
        if (msg.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(msg, color = Color.White, fontSize = 11.sp)
        }
    }
}

/** 설정 탭: 기록을 가져오는 곳(삼성 헬스 / Health Connect)을 바꾼다 */
@Composable
fun DataSourceCard() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val current = prefs.dataSource
    var pending by remember { mutableStateOf<String?>(null) }
    val names = linkedMapOf(
        DataSourceKind.SAMSUNG to "삼성 헬스",
        DataSourceKind.HEALTH_CONNECT to "Health Connect (가민 등)",
    )
    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("데이터 소스")
        Spacer(Modifier.height(4.dp))
        Text("지금: ${names[current] ?: current}", color = Color.White, fontSize = 12.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            "가민 워치를 쓰면 가민 커넥트 앱에서 Health Connect 연동을 켜고, 여기서 Health Connect를 고르세요. " +
                "소스를 바꾸면 이 앱에 저장된 기록 사본을 지우고 다시 읽어 와요. 삼성 헬스와 Health Connect의 원본은 그대로예요.",
            color = RunColors.TextDim, fontSize = 10.sp, lineHeight = 15.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            names.forEach { (key, label) ->
                if (key != current) {
                    OutlinedButton(
                        onClick = { pending = key },
                        modifier = Modifier.height(34.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    ) { Text("$label 로 바꾸기", color = RunColors.Lime, fontSize = 12.sp) }
                }
            }
        }
    }
    pending?.let { target ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("데이터 소스를 바꿀까요?") },
            text = { Text("저장된 기록 사본을 지우고 ${names[target]}에서 다시 읽어 와요. 처음에는 시간이 조금 걸릴 수 있어요.") },
            confirmButton = {
                TextButton(onClick = {
                    prefs.dataSource = target
                    java.io.File(context.filesDir, "runs_cache.json").delete()
                    java.io.File(context.filesDir, "health_cache.json").delete()
                    pending = null
                    (context as? android.app.Activity)?.recreate()
                }) { Text("바꾸기") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("취소") } },
        )
    }
}

@Composable
fun ProfileCard(onSaved: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var showEdit by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf(0) }   // 저장 후 요약 문구를 다시 그리기 위한 값
    var anchorIso by remember { mutableStateOf(prefs.trainingStart) }
    val p = remember(version) { UserProfile.data }

    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("내 정보")
        Spacer(Modifier.height(4.dp))
        val ageText = UserProfile.age?.let { "${it}세 · " } ?: ""
        Text(
            "${UserProfile.NAME} · $ageText${p.heightCm}cm ${p.weightKg}kg",
            color = Color.White, fontSize = 12.sp,
        )
        Text(
            "목표 ${UserProfile.goalLabel()} · 주 ${UserProfile.RUN_DAYS}회 · 최대 심박 ${UserProfile.MAX_HR}bpm" +
                (if (p.maxHr > 0) "" else " (추정)"),
            color = RunColors.TextDim, fontSize = 11.sp,
        )
        if (anchorIso.isNotBlank()) {
            val a = java.time.LocalDate.parse(anchorIso)
            Spacer(Modifier.height(4.dp))
            Text(
                "훈련 1주차 시작: ${a.monthValue}/${a.dayOfMonth}(월) · 지금은 ${PlanEngine.weekNumber(a, java.time.LocalDate.now())}주차",
                color = RunColors.TextDim, fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (anchorIso.isNotBlank()) {
                OutlinedButton(
                    onClick = {
                        val a = java.time.LocalDate.parse(anchorIso)
                        android.app.DatePickerDialog(context, { _, y, mo, d ->
                            val monday = PlanEngine.mondayOf(java.time.LocalDate.of(y, mo + 1, d))
                            prefs.trainingStart = monday.toString()
                            anchorIso = monday.toString()
                            Toast.makeText(context, "선택한 날짜가 속한 주(월요일 시작)가 1주차예요", Toast.LENGTH_SHORT).show()
                            onSaved()
                        }, a.year, a.monthValue - 1, a.dayOfMonth).show()
                    },
                    modifier = Modifier.height(34.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                ) { Text("1주차 시작일", color = RunColors.Lime, fontSize = 12.sp) }
            }
            OutlinedButton(
                onClick = { showEdit = true },
                modifier = Modifier.height(34.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            ) { Text("내 정보 수정", color = RunColors.Lime, fontSize = 12.sp) }
            OutlinedButton(
                onClick = { showClear = true },
                modifier = Modifier.height(34.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            ) { Text("내 데이터 모두 지우기", color = RunColors.Pink, fontSize = 12.sp) }
        }
    }

    if (showEdit) {
        val form = remember { ProfileForm(UserProfile.data) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showEdit = false },
            containerColor = RunColors.Card,
            titleContentColor = Color.White,
            textContentColor = RunColors.TextDim,
            title = { Text("내 정보 수정", fontSize = 17.sp) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    BodyFields(form)
                    GoalFields(form)
                    error?.let { Text(it, color = RunColors.Pink, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    error = form.validateBody() ?: form.validateGoal()
                    if (error == null) {
                        val np = form.toProfile(onboarded = true)
                        prefs.saveProfile(np)
                        UserProfile.data = np
                        version++
                        showEdit = false
                        Toast.makeText(context, "저장했어요. 계획에 바로 반영했어요 (기록이 없으면 '새 계획'을 눌러주세요)", Toast.LENGTH_LONG).show()
                        onSaved()
                    }
                }) { Text("저장", color = RunColors.Lime) }
            },
            dismissButton = { TextButton(onClick = { showEdit = false }) { Text("취소", color = RunColors.TextDim) } },
        )
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            containerColor = RunColors.Card,
            titleContentColor = Color.White,
            textContentColor = RunColors.TextDim,
            title = { Text("내 데이터를 모두 지울까요?", fontSize = 17.sp) },
            text = {
                Text(
                    "이 폰에 저장된 내 정보, 훈련 계획, 러닝 기록 저장본, 파일에서 가져온 기록, 건강 요약, 체중·체성분 이력, AI 평가, API 키, 캘린더 연결 정보가 모두 삭제돼요. " +
                        "삼성 헬스와 구글 캘린더에 있는 원본 데이터는 그대로예요. 삭제하면 앱이 처음 상태로 돌아가요.",
                    fontSize = 12.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.reminderOn = false
                    Reminder.schedule(context)      // 예약된 알림을 먼저 취소
                    prefs.clearAll()
                    listOf("runs_cache.json", "health_cache.json", "body_cache.json", "imported_runs.json").forEach { java.io.File(context.filesDir, it).delete() }
                    UserProfile.data = ProfileData()
                    showClear = false
                    Toast.makeText(context, "삭제했어요. 앱을 다시 열어 주세요", Toast.LENGTH_LONG).show()
                    (context as? android.app.Activity)?.finishAffinity()
                }) { Text("모두 삭제", color = RunColors.Pink) }
            },
            dismissButton = { TextButton(onClick = { showClear = false }) { Text("취소", color = RunColors.TextDim) } },
        )
    }
}

/** 개인정보처리방침 주소. 배포하기 전에 실제 주소로 바꿔 넣으세요 (비워 두면 링크를 숨겨요). */
const val PRIVACY_POLICY_URL = "https://jihoon0501.github.io/runcoach/privacy.html"

/** 설정: 앱 버전, 의료 면책, 개인정보 안내 */
@Composable
fun AboutCard() {
    val context = LocalContext.current
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }
    var taps by remember { mutableStateOf(0) }
    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("앱 정보")
        Spacer(Modifier.height(4.dp))
        if (version.isNotBlank()) {
            Text(
                "버전 $version", color = RunColors.TextDim, fontSize = 11.sp,
                modifier = Modifier.clickable { taps++ },   // 5번 누르면 개발자용 정보(패키지명, 서명 지문)가 보여요
            )
        }
        if (taps >= 5) {
            val sig = remember { AppSignature.sha256(context) ?: "확인할 수 없어요" }
            Spacer(Modifier.height(4.dp))
            Text("패키지명: ${context.packageName}", color = RunColors.TextDim, fontSize = 10.sp)
            Text("서명 SHA-256: $sig", color = RunColors.TextDim, fontSize = 10.sp, lineHeight = 14.sp)
            Text(
                "지문 복사",
                color = RunColors.Lime, fontSize = 10.sp,
                modifier = Modifier.clickable {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("sha256", sig))
                    Toast.makeText(context, "복사했어요", Toast.LENGTH_SHORT).show()
                },
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "이 앱의 계획·평가·컨디션 점수는 일반적인 운동 가이드이며 의료 조언이 아니에요. " +
                "가슴 통증, 어지러움, 비정상적인 심박 같은 증상이 있으면 운동을 멈추고 전문가와 상담하세요.",
            color = RunColors.TextDim, fontSize = 10.sp, lineHeight = 14.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "내 정보와 건강 데이터는 이 폰 안에만 저장돼요. Claude API 키를 입력한 경우에만 평가 요청 때 러닝 기록 요약과 나이·키·체중·목표 같은 기본 정보가 Anthropic으로 전송돼요.",
            color = RunColors.TextDim, fontSize = 10.sp, lineHeight = 14.sp,
        )
        if (PRIVACY_POLICY_URL.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "개인정보처리방침 보기",
                color = RunColors.Lime, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(PRIVACY_POLICY_URL))
                    )
                },
            )
        }
    }
}

// ─── 직접 백업 / 복원 카드 ────────────────────────────────────────────────────
@Composable
fun BackupRestoreCard() {
    val context = LocalContext.current
    val prefs   = remember { Prefs(context) }
    val scope   = rememberCoroutineScope()
    var status  by remember { mutableStateOf("") }

    // JSON 직렬화 — SharedPreferences + filesDir JSON 모두 하나의 파일로
    fun buildBackupJson(): String {
        val sp = context.getSharedPreferences("runlog", android.content.Context.MODE_PRIVATE)
        val root = org.json.JSONObject()
        root.put("version", 1)
        root.put("exportedAt", java.time.Instant.now().toString())
        // SharedPreferences 전체
        val spObj = org.json.JSONObject()
        for ((k, v) in sp.all) {
            when (v) {
                is String  -> spObj.put(k, v)
                is Boolean -> spObj.put(k, v)
                is Int     -> spObj.put(k, v)
                is Long    -> spObj.put(k, v)
                is Float   -> spObj.put(k, v)
                else       -> {}
            }
        }
        root.put("prefs", spObj)
        // filesDir JSON 파일들
        val files = org.json.JSONObject()
        listOf("runs_cache.json","health_cache.json","body_cache.json","imported_runs.json").forEach { name ->
            val f = java.io.File(context.filesDir, name)
            if (f.exists()) files.put(name, f.readText())
        }
        root.put("files", files)
        return root.toString(2)
    }

    fun restoreFromJson(json: String) {
        try {
            val root = org.json.JSONObject(json)
            val sp   = context.getSharedPreferences("runlog", android.content.Context.MODE_PRIVATE)
            val edit = sp.edit()
            val spObj = root.optJSONObject("prefs")
            if (spObj != null) {
                // Long 키 목록 — JSON은 Long도 Int로 파싱하므로 명시적으로 putLong
                val longKeys = setOf("cal_id")
                for (k in spObj.keys()) {
                    when {
                        k in longKeys -> edit.putLong(k, (spObj.get(k) as Number).toLong())
                        else -> when (val v = spObj.get(k)) {
                            is String  -> edit.putString(k, v)
                            is Boolean -> edit.putBoolean(k, v)
                            is Int     -> edit.putInt(k, v)
                            is Long    -> edit.putLong(k, v)
                            is Double  -> edit.putFloat(k, v.toFloat())
                            else       -> {}
                        }
                    }
                }
                edit.apply()
            }
            val files = root.optJSONObject("files")
            if (files != null) {
                for (name in files.keys()) {
                    java.io.File(context.filesDir, name).writeText(files.getString(name))
                }
            }
            status = "✅ 복원 완료! 앱을 재시작해 주세요."
        } catch (e: Exception) {
            status = "❌ 복원 실패: ${e.message}"
        }
    }

    // 파일 저장 launcher
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(buildBackupJson().toByteArray(Charsets.UTF_8))
            }
            status = "✅ 백업 완료!"
        } catch (e: Exception) {
            status = "❌ 백업 실패: ${e.message}"
        }
    }

    // 파일 열기 launcher
    val openLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val json = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.readText() ?: return@rememberLauncherForActivityResult
            restoreFromJson(json)
        } catch (e: Exception) {
            status = "❌ 파일 열기 실패: ${e.message}"
        }
    }

    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("직접 백업 / 복원")
        Spacer(Modifier.height(4.dp))
        Text(
            "훈련 계획, AI 평가, 프로필, 기록 캐시를 JSON 파일로 저장해요. 재설치 전에 내보내고, 설치 후 가져오면 돼요.",
            color = RunColors.TextDim, fontSize = 11.sp, lineHeight = 16.sp,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 내보내기
            Button(
                onClick = {
                    val date = java.time.LocalDate.now().toString()
                    saveLauncher.launch("runcoach_backup_$date.json")
                },
                modifier = Modifier.weight(1f).height(40.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = RunColors.Violet,
                ),
            ) {
                Text("⬆ 내보내기", fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
            // 가져오기
            OutlinedButton(
                onClick = { openLauncher.launch(arrayOf("application/json", "*/*")) },
                modifier = Modifier.weight(1f).height(40.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, RunColors.Lime.copy(alpha = 0.6f)),
            ) {
                Text("⬇ 가져오기", fontSize = 12.sp, color = RunColors.Lime)
            }
        }
        if (status.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(status, color = if (status.startsWith("✅")) RunColors.Lime else RunColors.Pink,
                fontSize = 11.sp)
        }
    }
}
