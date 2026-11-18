package io.github.jihoon0501.runcoach

import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePicker
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.PermissionController
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
    var raceDate by mutableStateOf(p.raceDate)
    var intensityLevel by mutableStateOf(p.intensityLevel)
    var seasonAware by mutableStateOf(p.seasonAware)
    var runDays by mutableStateOf(p.runDays.coerceIn(3, 7))
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
        // 출생연도 필수
        val y = birthYear.toIntOrNull()
        if (birthYear.isBlank() || y == null || y !in 1930..(LocalDate.now().year - 10))
            return "출생연도를 입력해 주세요 (예: 1990)"
        // 최대 심박: 비워 두면 220-나이로 자동 계산, 직접 입력 시 범위 확인
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
            raceDate = if (noGoal) null else raceDate,
            intensityLevel = intensityLevel,
            seasonAware = seasonAware,
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
    placeholder: String = "",
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(4)) },
        label = { Text(label) },
        suffix = if (suffix.isNotEmpty()) ({ Text(suffix, color = RunColors.TextDim) }) else null,
        placeholder = if (placeholder.isNotEmpty()) ({ Text(placeholder, color = RunColors.TextDim, fontSize = 14.sp) }) else null,
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
            NumberField("출생연도 *", f.birthYear, { f.birthYear = it }, Modifier.weight(1f))
            // 최대 심박 필드: 비워 두면 220-나이로 자동 계산, 직접 입력도 허용
            val autoHr = f.estimatedMaxHr()
            NumberField(
                label = "최대 심박 (선택)",
                value = f.maxHr,
                onChange = { f.maxHr = it },
                modifier = Modifier.weight(1f),
                suffix = "bpm",
                placeholder = "$autoHr",
            )
        }
        // 출생연도가 있으면 자동 계산값 실시간 표시
        val birthYearInt = f.birthYear.toIntOrNull()
        if (birthYearInt != null && birthYearInt in 1930..(LocalDate.now().year - 10)) {
            val age = LocalDate.now().year - birthYearInt
            val autoMax = 220 - age
            if (f.maxHr.isBlank()) {
                Hint("나이 ${age}세 기준 권장 최대 심박: ${autoMax}bpm (220 − ${age}). 비워 두면 이 값으로 자동 계산돼요.")
            } else {
                Hint("직접 입력한 최대 심박 ${f.maxHr}bpm을 사용해요. 워치에서 측정한 값이면 더 정확해요.")
            }
        } else {
            Hint("출생연도를 입력하면 최대 심박(220 − 나이)이 자동 계산돼요. 워치에서 직접 측정한 값이 있으면 입력하세요.")
        }
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

            if (f.goalKey == "하프" || f.goalKey == "풀") {
                val rd = f.raceDate
                Label("목표 대회 날짜 (선택)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    var showPicker by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = { showPicker = true },
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) {
                        Text(
                            if (rd != null) "${rd.year}년 ${rd.monthValue}월 ${rd.dayOfMonth}일" else "날짜를 고르세요",
                            color = if (rd != null) RunColors.Violet else RunColors.TextDim,
                            fontSize = 13.sp,
                        )
                    }
                    if (rd != null) {
                        TextButton(onClick = { f.raceDate = null }) {
                            Text("지우기", color = RunColors.TextDim, fontSize = 11.sp)
                        }
                    }
                    if (showPicker) {
                        RaceDatePickerDialog(
                            initial = rd ?: LocalDate.now().plusMonths(4),
                            onPick = { f.raceDate = it; showPicker = false },
                            onDismiss = { showPicker = false },
                        )
                    }
                }
                if (rd != null) {
                    val weeks = java.time.temporal.ChronoUnit.WEEKS.between(LocalDate.now(), rd).toInt().coerceAtLeast(0)
                    val phase = PlanEngine.racePhaseLabel(weeks, f.goalKey)
                    Hint("오늘부터 ${weeks}주 남았어요. 현재 훈련 단계: $phase")
                } else {
                    Hint("대회 날짜를 고르면 단계별 계획(기초 → 빌드업 → 피크 → 테이퍼)을 자동으로 짜줘요. 비워 두면 일반 계획이에요.")
                }
            }
        } else {
            Hint("목표 기록 없이 꾸준히 달리는 걸 목표로 해요. 최근 기록을 기준으로 강도를 정해요.")
        }

        Spacer(Modifier.height(12.dp))
        Label("훈련 강도")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(-1 to "여유롭게", 0 to "기본", 1 to "도전적으로").forEach { (v, lbl) ->
                Chip(lbl, f.intensityLevel == v) { f.intensityLevel = v }
            }
        }
        Hint(when (f.intensityLevel) {
            -1 -> "계획이 너무 힘들면 여기서 낮추세요. 롱런과 주간 거리를 20% 줄여요."
            1  -> "현재 실력보다 10% 더 도전적인 계획이 나와요. 충분한 수면과 영양이 필요해요."
            else -> "현재 러닝 기록과 주간 거리를 기반으로 적절한 계획을 짜요."
        })

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("한국 계절성 반영", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (f.seasonAware) "여름(7~8월) 훈련량 감소 · 봄·가을 소폭 증가"
                    else "계절 무관하게 일정한 계획으로 진행",
                    color = RunColors.TextDim, fontSize = 11.sp,
                )
            }
            Switch(
                checked = f.seasonAware,
                onCheckedChange = { f.seasonAware = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = RunColors.Lime,
                    checkedTrackColor = RunColors.Lime.copy(alpha = 0.4f),
                ),
            )
        }
        Hint(if (f.seasonAware)
            "7~8월 폭염·장마 시기에 주간 거리와 롱런을 최대 28% 줄이고 여름 러닝 조언을 계획에 넣어요."
        else "실내 트레드밀 위주이거나 계절 영향을 받지 않는 환경이라면 꺼두세요.")

        Label("일주일에 몇 번 달리고 싶으세요?")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(3, 4, 5, 6, 7).forEach { n -> Chip("주 ${n}회", f.runDays == n) { f.runDays = n } }
        }
        Hint("주 ${f.runDays}회. 하프·풀코스 목표라면 5~6회를 추천해요. 한 번에 10km 이상보다 여러 날로 분산하면 부상 위험이 줄어요.")

        Label("요즘 일주일에 달리는 거리 (km)")
        // 슬라이더 → 숫자 직접 입력 (80km 이상도 지원)
        var weeklyKmText by remember { mutableStateOf(if (f.weeklyKm > 0f) f.weeklyKm.roundToInt().toString() else "") }
        OutlinedTextField(
            value = weeklyKmText,
            onValueChange = { v ->
                weeklyKmText = v.filter { it.isDigit() }.take(4)
                weeklyKmText.toIntOrNull()?.let { f.weeklyKm = it.toFloat() }
            },
            modifier = Modifier.fillMaxWidth(),
            suffix = { Text("km", color = RunColors.TextDim) },
            placeholder = { Text("예: 30", color = RunColors.TextDim, fontSize = 14.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            colors = fieldColors(),
        )
        Hint("러닝 기록이 충분히 쌓이기 전까지만 기준으로 써요. 처음 시작이라면 0~10km, 주 5회 이상 달린다면 실제 주간 거리를 입력하세요.")

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
    var hcMsg by remember { mutableStateOf("") }
    var source by remember { mutableStateOf(prefs.dataSource) }
    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        val ok = granted.contains(HealthPermission.getReadPermission(ExerciseSessionRecord::class))
        hcMsg = if (ok) "연결됐어요 ✅ (허용 ${granted.size}개)" else "허용하지 않았어요. 나중에 설정 탭에서 다시 시도할 수 있어요"
    }
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
                        Text("RunCoach에 오신 걸 환영해요 👋", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        Text(
                            "삼성 헬스의 러닝 기록과 수면·심박 같은 건강 신호를 함께 보고, 내 몸 상태에 맞는 훈련 계획과 피드백을 만들어 드려요.",
                            fontSize = 13.sp, color = RunColors.TextDim, lineHeight = 19.sp,
                        )
                        GlassCard(Modifier.fillMaxWidth()) {
                            SectionTitle("시작하기 전에 알아 두세요")
                            Spacer(Modifier.height(6.dp))
                            listOf(
                                "내 정보와 건강 데이터는 이 폰 안에서만 저장하고 계산해요. 서버로 보내지 않아요.",
                                "읽는 데이터: 러닝·걷기 기록, 수면, 심박 (삼성 헬스에서는 에너지 점수, 피부 온도도). 삼성 헬스 또는 Health Connect에서 읽기만 하고 수정하지 않아요. 원하면 '신체' 탭에서 체중·키·체지방·골격근량도 가져올 수 있고, 가져오면 내 정보의 키·체중도 새 측정값으로 자동 갱신돼요(선택).",
                                "Claude API 키를 직접 입력해서 AI 평가를 켜면, 그때만 러닝 기록 요약·최근 이력과 나이·키·체중·목표 같은 기본 정보가 Anthropic 서버로 전송돼요. 이름과 수면·심박 같은 건강 수치는 보내지 않고, 사용량만큼 본인에게 요금이 부과돼요.",
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
                            "러닝 기록과 건강 신호를 어디서 읽어 올지 고르세요. 각 버튼을 누르면 허용 창이 떠요. 건너뛰어도 되고, 나중에 설정 탭에서 바꿀 수 있어요.",
                            fontSize = 12.sp, color = RunColors.TextDim, lineHeight = 18.sp,
                        )
                        Label("데이터를 가져올 곳")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("삼성 헬스", source == DataSourceKind.SAMSUNG) {
                                source = DataSourceKind.SAMSUNG
                                prefs.dataSource = source
                            }
                            Chip("Health Connect (가민 등)", source == DataSourceKind.HEALTH_CONNECT) {
                                source = DataSourceKind.HEALTH_CONNECT
                                prefs.dataSource = source
                            }
                        }
                        if (source == DataSourceKind.SAMSUNG) {
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
                        } else {
                            GlassCard(Modifier.fillMaxWidth()) {
                                SectionTitle("Health Connect 연결")
                                Spacer(Modifier.height(4.dp))
                                Hint(
                                    "가민 커넥트, 삼성 헬스 등에서 Health Connect로 기록을 보내도록 먼저 설정해 주세요. " +
                                        "이 앱은 Health Connect에서 운동, 심박, 거리, 속도, 걸음 수, 수면을 읽기만 해요. " +
                                        "앱마다 넣어 주는 항목이 달라서 구간별 페이스나 케이던스가 없을 수 있어요."
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        if (HealthConnectRepo.isAvailable(context)) {
                                            hcLauncher.launch(HealthConnectRepo.PERMISSIONS)
                                        } else {
                                            hcMsg = "Health Connect를 설치하거나 업데이트한 뒤 다시 눌러 주세요"
                                            runCatching { context.startActivity(HealthConnectRepo.storeIntent()) }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = RunColors.Lime, contentColor = RunColors.Bg),
                                ) { Text("Health Connect 허용하기", fontWeight = FontWeight.Bold) }
                                if (hcMsg.isNotEmpty()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(hcMsg, fontSize = 11.sp, color = Color.White)
                                }
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
                            Hint("입력하면 평가할 때마다 러닝 기록 요약, 최근 이력, 나이·키·체중·목표 같은 기본 정보가 Anthropic 서버로 전송되고, 사용량만큼 요금이 부과돼요. 키는 이 폰에만 저장돼요.")
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



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RaceDatePickerDialog(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val initMs = initial.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    val state = rememberDatePickerState(initialSelectedDateMillis = initMs)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { ms ->
                    val d = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    onPick(d)
                }
            }) { Text("확인") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    ) { DatePicker(state = state) }
}
