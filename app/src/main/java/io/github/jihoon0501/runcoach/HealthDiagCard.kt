package io.github.jihoon0501.runcoach

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import kotlinx.coroutines.launch

/** 설정 탭: 수면·심박·에너지 점수 등 건강 데이터가 실제로 읽히는지 확인하는 진단 카드 */
@Composable
fun HealthDiagCard() {
    val context = LocalContext.current
    val diag = remember { HealthDiag(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var stats by remember { mutableStateOf<List<HealthTypeStat>>(emptyList()) }
    var status by remember { mutableStateOf("① 권한 요청 → ② 진단 실행 순서로 눌러 주세요") }
    var running by remember { mutableStateOf(false) }

    val small = PaddingValues(horizontal = 12.dp, vertical = 0.dp)

    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("건강 데이터 진단")
        Spacer(Modifier.height(3.dp))
        Text(
            "맞춤형 러닝 추천을 위해 수면·심박·에너지 점수 등이 최근에 얼마나 기록돼 있는지 확인해요. " +
                "개수와 평균만 계산하고 원시 기록은 저장하지 않아요.",
            color = RunColors.TextDim, fontSize = 10.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    scope.launch {
                        status = try {
                            val n = diag.requestAll(context as Activity)
                            "권한 허용 $n/${diag.total}개 — 이제 '진단 실행'을 눌러 주세요"
                        } catch (e: Throwable) {
                            "오류: ${e::class.java.simpleName}: ${e.message}"
                        }
                    }
                },
                modifier = Modifier.height(34.dp),
                contentPadding = small,
                colors = ButtonDefaults.buttonColors(containerColor = RunColors.Lime, contentColor = RunColors.Bg),
            ) { Text("권한 요청", fontWeight = FontWeight.Bold, fontSize = 12.sp) }

            OutlinedButton(
                onClick = {
                    if (running) return@OutlinedButton
                    scope.launch {
                        running = true
                        status = "읽는 중..."
                        try {
                            stats = diag.run(30)
                            status = "진단 완료 (기본 30일, 심박 7일, 체성분 90일 기준)"
                        } catch (e: Throwable) {
                            status = "오류: ${e::class.java.simpleName}: ${e.message}"
                        }
                        running = false
                    }
                },
                modifier = Modifier.height(34.dp),
                contentPadding = small,
            ) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(12.dp), color = RunColors.Lime, strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text("진단 실행", color = RunColors.Lime, fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = {
                    if (stats.isNotEmpty()) {
                        clipboard.setText(AnnotatedString(diag.toJson(stats)))
                        status = "진단 결과 JSON을 복사했어요"
                    }
                },
                modifier = Modifier.height(34.dp),
                contentPadding = small,
            ) { Text("JSON 복사", color = RunColors.Lime, fontSize = 12.sp) }
        }
        Spacer(Modifier.height(6.dp))
        Text(status, color = Color.White, fontSize = 11.sp)

        stats.forEach { s ->
            Spacer(Modifier.height(6.dp))
            val icon = when {
                s.error != null -> "❌"
                !s.granted -> "🔒"
                s.points == 0 -> "⚠️"
                else -> "✅"
            }
            Row(verticalAlignment = Alignment.Top) {
                Text(icon, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
                Column {
                    Text(
                        if (s.granted && s.error == null) "${s.label} · ${s.days}/${s.windowDays}일 기록"
                        else s.label,
                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        s.error ?: s.summary,
                        color = if (s.error != null) RunColors.Pink else RunColors.TextDim,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}
