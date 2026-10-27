package io.github.jihoon0501.runcoach

import android.Manifest
import android.app.Activity
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

// ───────────────────────── 입력 폼 상태 ─────────────────────────

private const val NO_GOAL = "기록 없이"
private val GOAL_KEYS = listOf("5K", "10K", "하프", "풀", NO_GOAL)

private fun goalDistanceOf(key: String): Double = when (key) {
    "5K" -> 5.0
    "10K" -> 10.0
    "하프" -> 21.0975
    "풀" -> 42.195
    else -> 10.0
}

private fun goalKeyOf(p: ProfileData): String = when {
    p.goalTimeSec <= 0 && p.onboarded -> NO_GOAL
    p.goalDistanceKm <= 5.1 -> "5K"
    p.goalDistanceKm <= 10.1 -> "10K"
    p.goalDistanceKm <= 21.2 -> "하프"
    else -> "풀"
}

/** 내 정보 입력 폼. 첫 실행 화면과 설정의 '내 정보 수정'에서 같이 쓴다. */
internal class ProfileForm(p: ProfileData) {
    var nickname by mutableStateOf(p.nickname)
    var birthYear by mutableStateOf(if (p.birthYear > 0) p.birthYear.toString() else "")
    var sex by mutableStateOf(p.sex)
    var height by mutableStateOf(if (p.onboarded) p.heightCm.toString() else "")
    var weight by mutableStateOf(if (p.onboarded) p.weightKg.toString() else "")
    var maxHr by mutableStateOf(if (p.maxHr > 0) p.maxHr.toString() else "")
    var goalKey by mutableStateOf(goalKeyOf(p))
    var gHour by mutableStateOf(if (p.goalTimeSec >= 3600) (p.goalTimeSec / 3600).toString() else "")
    var gMin by mutableStateOf(if (p.goalTimeSec > 0) ((p.goalTimeSec % 3600) / 60).toString() else "")
    var gSec by mutableStateOf(if (p.goalTimeSec > 0) (p.goalTimeSec % 60).toString() else "")
    var runDays by mutableStateOf(p.runDays.coerceIn(3, 4))
    var weeklyKm by mutableStateOf(p.weeklyKm.toFloat())
    var jointCare by mutableStateOf(p.jointCare)

    private fun goalTotalSec(): Int =
        (gHour.toIntOrNull() ?: 0) * 3600 + (gMin.toIntOrNull() ?: 0) * 60 + (gSec.toIntOrNull() ?: 0)

    /** 입력값 검사. 문제가 없으면 null, 있으면 안내 문구 */
    fun validateBody(): String? {
        val h = height.toIntOrNull()
        val w = weight.toIntOrNull()
        if (h == null || h !in 120..230) return "키를 120~230cm 사이로 입력해 주세요"
        if (w == null || w !in 30..250) return "체중을 30~250kg 사이로 입력해 주세요"
        if (birthYear.isNotBlank()) {
            val y = birthYear.toIntOrNull()
            if (y == null || y !in 1930..(LocalDate.now().year - 10)) return "출생연도를 확인해 주세요 (예: 1990)"
        }
        if (maxHr.isNotBlank()) {
            val m = maxHr.toIntOrNull()
            if (m == null || m !in 120..230) return "최대 심박은 120~230 사이로 입력하거나 비워 두세요"
        }
        return null
    }

    fun validateGoal(): String? {
        if (goalKey != NO_GOAL) {
            val total = goalTotalSec()
            if (total > 0) {
                val pace = total / goalDistanceOf(goalKey)
                if (pace < 150.0 || pace > 720.0) return "목표 기록이 현실적이지 않아요 (1km당 2:30~12:00 사이로 입력해 주세요)"
            }
        }
        return null
    }

    fun toProfile(onboarded: Boolean): ProfileData {
        val noGoal = goalKey == NO_GOAL || goalTotalSec() <= 0
        return ProfileData(
            nickname = nickname.trim(),
            birthYear = birthYear.toIntOrNull() ?: 0,
            sex = sex,
            heightCm = height.toIntOrNull() ?: 170,
            weightKg = weight.toIntOrNull() ?: 65,
            maxHr = maxHr.toIntOrNull() ?: 0,
            goalDistanceKm = goalDistanceOf(goalKey),
            goalTimeSec = if (noGoal) 0 else goalTotalSec(),
            runDays = runDays,
            weeklyKm = weeklyKm.roundToInt(),
            jointCare = jointCare,
            onboarded = onboarded,
        )
    }

    /** 최대 심박을 비워 둘 때 쓰게 될 추정값 */
    fun estimatedMaxHr(): Int {
        val y = birthYear.toIntOrNull()
        return if (y != null && y in 1930..LocalDate.now().year) (208 - 0.7 * (LocalDate.now().year - y)).roundToInt() else 185
    }
}

// ───────────────────────── 공통 입력 컴포넌트 ─────────────────────────

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = RunColors.Lime,
    unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
    focusedLabelColor = RunColors.Lime,
    unfocusedLabelColor = RunColors.TextDim,
    cursorColor = RunColors.Lime,
)

@Composable
private fun NumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String = "",
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(4)) },
        label = { Text(label) },
        suffix = if (suffix.isNotEmpty()) ({ Text(suffix, color = RunColors.TextDim) }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
        colors = fieldColors(),
    )
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) RunColors.Lime else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) RunColors.Bg else Color.White,
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 10.sp, color = RunColors.TextDim, lineHeight = 14.sp)
}

/** 이름, 출생연도, 성별, 키, 체중, 최대 심박 */
@Composable
internal fun BodyFields(f: ProfileForm) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = f.nickname,
            onValueChange = { f.nickname = it.take(12) },
            label = { Text("닉네임 (선택)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField("출생연도 (선택)", f.birthYear, { f.birthYear = it }, Modifier.weight(1f))
            NumberField("최대 심박 (선택)", f.maxHr, { f.maxHr = it }, Modifier.weight(1f), suffix = "bpm")
        }
        Hint("최대 심박을 모르면 비워 두세요. 출생연도로 추정해요 (현재 추정 ${f.estimatedMaxHr()}bpm). 워치에서 측정한 최대 심박이 있으면 그 값이 더 정확해요.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField("키", f.height, { f.height = it }, Modifier.weight(1f), suffix = "cm")
            NumberField("체중", f.weight, { f.weight = it }, Modifier.weight(1f), suffix = "kg")
        }
        Label("성별 (선택)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("선택 안 함", f.sex == "") { f.sex = "" }
            Chip("남성", f.sex == "M") { f.sex = "M" }
            Chip("여성", f.sex == "F") { f.sex = "F" }
        }
    }
}

/** 목표 종목/기록, 주당 러닝 횟수, 요즘 주간 거리, 관절 주의 */
@Composable
internal fun GoalFields(f: ProfileForm) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label("목표 종목")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GOAL_KEYS.forEach { k -> Chip(k, f.goalKey == k) { f.goalKey = k } }
        }
        if (f.goalKey != NO_GOAL) {
            Label("목표 기록 (모르면 비워 두세요)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (f.goalKey == "하프" || f.goalKey == "풀") {
                    NumberField("시간", f.gHour, { f.gHour = it }, Modifier.weight(1f))
                }
                NumberField("분", f.gMin, { f.gMin = it }, Modifier.weight(1f))
                NumberField("초", f.gSec, { f.gSec = it }, Modifier.weight(1f))
            }
            Hint("기록을 비워 두면 최근 러닝 기록을 기준으로 목표를 자동으로 잡아요.")
        } else {
            Hint("목표 기록 없이 꾸준히 달리는 걸 목표로 해요. 최근 기록을 기준으로 강도를 정해요.")
        }

        Label("일주일에 몇 번 달리고 싶으세요?")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("주 3회", f.runDays == 3) { f.runDays = 3 }
            Chip("주 4회", f.runDays == 4) { f.runDays = 4 }
        }
        Hint("주 4회를 고르면 일요일에 가벼운 선택 훈련이 추가돼요. 이 횟수를 넘겨 달리면 쉬어가도록 안내해요.")

        Label("요즘 일주일에 달리는 거리: ${f.weeklyKm.roundToInt()}km")
        Slider(
            value = f.weeklyKm,
            onValueChange = { f.weeklyKm = it },
            valueRange = 0f..80f,
            colors = SliderDefaults.colors(
                thumbColor = RunColors.Lime,
                activeTrackColor = RunColors.Lime,
                inactiveTrackColor = Color.White.copy(alpha = 0.15f),
            ),
        )
        Hint("러닝 기록이 충분히 쌓이기 전까지만 기준으로 써요. 처음 시작이라면 0~10km 정도로 두세요.")

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("무릎·관절 부담 줄이기", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                Hint("강한 훈련 사이 휴식을 더 챙기고 안내 문구에 반영해요")
            }
            Switch(
                checked = f.jointCare,
                onCheckedChange = { f.jointCare = it },
                colors = SwitchDefaults.colors(checkedTrackColor = RunColors.Lime, checkedThumbColor = RunColors.Bg),
            )
        }
    }
}

// ───────────────────────── 첫 실행 화면 ─────────────────────────

private const val LAST_STEP = 5

@Composable
fun OnboardingScreen(prefs: Prefs, samsung: SamsungRepo, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableStateOf(0) }
    var agreed by rememberSaveable { mutableStateOf(false) }
    val form = remember { ProfileForm(UserProfile.data) }
    var error by remember { mutableStateOf<String?>(null) }
    var samsungMsg by remember { mutableStateOf("") }
    var healthMsg by remember { mutableStateOf("") }
    var notifMsg by remember { mutableStateOf("") }
    var reminderOn by remember { mutableStateOf(prefs.reminderOn) }
    var apiKey by remember { mutableStateOf(prefs.apiKey) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        notifMsg = if (ok) "알림을 허용했어요" else "알림이 꺼져 있어요. 나중에 폰 설정에서 켤 수 있어요"
    }

    fun next() {
        error = when (step) {
            0 -> if (!agreed) "안내를 확인하고 체크해 주세요" else null
            1 -> form.validateBody()
            2 -> form.validateGoal()
            else -> null
        }
        if (error == null && step < LAST_STEP) step++
    }

    fun finish() {
        val p = form.toProfile(onboarded = true)
        prefs.saveProfile(p)
        UserProfile.data = p
        prefs.reminderOn = reminderOn
        prefs.apiKey = apiKey.trim()
        onDone()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(RunColors.BgTop, RunColors.Bg)))
            .safeDrawingPadding(),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
            // 진행 표시
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (i in 0..LAST_STEP) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (i <= step) RunColors.Lime else Color.White.copy(alpha = 0.12f)),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (step) {
                    0 -> {
                        Text("RunLog에 오신 걸 환영해요 👋", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        Text(
                            "삼성 헬스의 러닝 기록과 수면·심박 같은 건강 신호를 함께 보고, 내 몸 상태에 맞는 훈련 계획과 피드백을 만들어 드려요.",
                            fontSize = 13.sp, color = RunColors.TextDim, lineHeight = 19.sp,
                        )
                        GlassCard(Modifier.fillMaxWidth()) {
                            SectionTitle("시작하기 전에 알아 두세요")
                            Spacer(Modifier.height(6.dp))
                            listOf(
                                "내 정보와 건강 데이터는 이 폰 안에서만 저장하고 계산해요. 서버로 보내지 않아요.",
                                "읽는 데이터: 러닝·걷기 기록, 수면, 심박, 에너지 점수, 피부 온도 (모두 읽기만 하고 수정하지 않아요).",
                                "Claude API 키를 직접 입력해서 AI 평가를 켜면, 그때만 러닝 기록 요약이 Anthropic 서버로 전송돼요. 이름은 보내지 않고, 사용량만큼 본인에게 요금이 부과돼요.",
                                "이 앱은 일반적인 운동 가이드이고 의료 조언이 아니에요. 가슴 통증, 어지러움, 비정상적인 심박이 있으면 훈련보다 전문가 상담이 먼저예요.",
                                "각 권한은 다음 단계에서 하나씩 설명하고 허용 여부를 직접 고르게 해요.",
                            ).forEach { Text("•  $it", fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f), lineHeight = 18.sp) }
                        }
                        Row(
                            Modifier.fillMaxWidth().clickable { agreed = !agreed },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = agreed,
                                onCheckedChange = { agreed = it },
                                colors = CheckboxDefaults.colors(checkedColor = RunColors.Lime, checkmarkColor = RunColors.Bg),
                            )
                            Text("위 내용을 확인했어요", fontSize = 13.sp, color = Color.White)
                        }
                    }
                    1 -> {
                        Text("내 정보", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        Hint("키·체중·나이는 운동 강도(심박 구간)와 안내 문구를 내 몸에 맞추는 데만 써요.")
                        BodyFields(form)
                    }
                    2 -> {
                        Text("목표와 훈련 방식", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        GoalFields(form)
                    }
                    3 -> {
                        Text("데이터 연결", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        Text(
                            "삼성 헬스에서 러닝 기록과 건강 신호를 읽어 와요. 각 버튼을 누르면 삼성 헬스의 허용 창이 떠요. 건너뛰어도 되고, 나중에 설정 탭에서 다시 할 수 있어요.",
                            fontSize = 12.sp, color = RunColors.TextDim, lineHeight = 18.sp,
                        )
                        GlassCard(Modifier.fillMaxWidth()) {
                            SectionTitle("① 러닝·걷기 기록")
                            Spacer(Modifier.height(4.dp))
                            Hint("삼성 헬스 앱이 설치되어 있고 로그인되어 있어야 해요. 거리, 페이스, 심박, 케이던스, 구간 기록을 읽어요.")
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    scope.launch {
                                        samsungMsg = try {
                                            if (samsung.requestPermission(context as Activity)) "연결됐어요 ✅" else "허용하지 않았어요. 나중에 설정 탭에서 다시 시도할 수 있어요"
                                        } catch (e: Throwable) {
                                            "연결하지 못했어요: ${e::class.java.simpleName}. 삼성 헬스 앱을 최신으로 업데이트했는지 확인해 주세요. " +
                                                "그래도 안 되면 이 앱이 아직 삼성 헬스 연동 등록을 마치지 않은 버전일 수 있어요."
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = RunColors.Lime, contentColor = RunColors.Bg),
                            ) { Text("삼성 헬스 연결하기", fontWeight = FontWeight.Bold) }
                            if (samsungMsg.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(samsungMsg, fontSize = 11.sp, color = Color.White)
                            }
                        }
                        GlassCard(Modifier.fillMaxWidth()) {
                            SectionTitle("② 수면·심박·에너지 점수 (선택)")
                            Spacer(Modifier.height(4.dp))
                            Hint("오늘 컨디션을 계산하는 데 써요. 허용하지 않으면 러닝 기록만으로 계획을 만들어요.")
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        healthMsg = try {
                                            val hd = HealthDaily(context.applicationContext)
                                            val n = hd.request(context as Activity)
                                            "허용 ${n}개 / 전체 ${hd.total}개 (수면, 심박, 에너지 점수, 피부 온도)"
                                        } catch (e: Throwable) {
                                            "허용하지 못했어요: ${e::class.java.simpleName}"
                                        }
                                    }
                                },
                            ) { Text("건강 데이터 허용하기", color = RunColors.Lime) }
                            if (healthMsg.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(healthMsg, fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }
                    4 -> {
                        Text("알림과 AI 평가 (선택)", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        GlassCard(Modifier.fillMaxWidth()) {
                            SectionTitle("러닝 알림")
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("저녁에 오늘 훈련 알려주기", fontSize = 12.sp, color = Color.White)
                                    Hint("알림 시각은 설정 탭에서 바꿀 수 있어요")
                                }
                                Switch(
                                    checked = reminderOn,
                                    onCheckedChange = { reminderOn = it },
                                    colors = SwitchDefaults.colors(checkedTrackColor = RunColors.Lime, checkedThumbColor = RunColors.Bg),
                                )
                            }
                            if (Build.VERSION.SDK_INT >= 33) {
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                                    Text("알림 권한 허용", color = RunColors.Lime)
                                }
                            }
                            if (notifMsg.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(notifMsg, fontSize = 11.sp, color = Color.White)
                            }
                        }
                        GlassCard(Modifier.fillMaxWidth()) {
                            SectionTitle("Claude AI 평가")
                            Spacer(Modifier.height(4.dp))
                            Hint("키가 없어도 기기 안에서 계산하는 기본 코치가 평가해요. Anthropic API 키를 입력하면 Claude가 더 자세히 평가해요.")
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                label = { Text("Anthropic API 키 (선택)") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                colors = fieldColors(),
                            )
                            Spacer(Modifier.height(6.dp))
                            Hint("입력하면 평가할 때마다 러닝 기록 요약과 최근 이력이 Anthropic 서버로 전송되고, 사용량만큼 요금이 부과돼요. 키는 이 폰에만 저장돼요.")
                        }
                        Hint("구글 캘린더 공유는 설정 탭에서 필요할 때 켤 수 있어요.")
                    }
                    else -> {
                        Text("준비 끝! 🎉", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        val p = form.toProfile(true)
                        GlassCard(Modifier.fillMaxWidth()) {
                            SectionTitle("입력한 내용")
                            Spacer(Modifier.height(6.dp))
                            val maxHr = if (p.maxHr > 0) p.maxHr else form.estimatedMaxHr()
                            listOf(
                                "닉네임: ${p.nickname.ifBlank { "러너" }}",
                                "키 ${p.heightCm}cm · 체중 ${p.weightKg}kg",
                                "최대 심박: ${maxHr}bpm" + if (p.maxHr > 0) "" else " (추정)",
                                "목표: " + if (p.goalTimeSec > 0) "${form.goalKey} ${formatDuration(p.goalTimeSec.toLong())}" else "${form.goalKey}${if (form.goalKey == NO_GOAL) "" else " (기록은 최근 기록 기준)"}",
                                "주 ${p.runDays}회 · 요즘 주간 거리 ${p.weeklyKm}km",
                            ).forEach { Text("•  $it", fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f), lineHeight = 18.sp) }
                        }
                        Hint("설정 탭의 '내 정보'에서 언제든 고칠 수 있어요. '시작하기'를 누르면 기록을 불러와서 첫 훈련 계획을 만들어요. 처음에는 기록을 읽는 데 시간이 조금 걸려요.")
                    }
                }
            }

            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = RunColors.Pink, fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) {
                    TextButton(onClick = { error = null; step-- }) { Text("이전", color = RunColors.TextDim) }
                }
                Spacer(Modifier.weight(1f))
                if (step == 3) {
                    TextButton(onClick = { error = null; step++ }) { Text("건너뛰기", color = RunColors.TextDim) }
                }
                Button(
                    onClick = { if (step == LAST_STEP) finish() else next() },
                    colors = ButtonDefaults.buttonColors(containerColor = RunColors.Lime, contentColor = RunColors.Bg),
                ) { Text(if (step == LAST_STEP) "시작하기" else "다음", fontWeight = FontWeight.Bold) }
            }
        }
    }
}
