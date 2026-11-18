package io.github.jihoon0501.runcoach

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 러닝 세션 간단 편집 다이얼로그.
 * 삼성 헬스 / Health Connect 원본은 그대로 — RunCache만 오버라이드.
 * 편집 가능: 거리(km), 시간(분·초), 메모.
 */
@Composable
fun RunEditDialog(
    run: RunSession,
    onConfirm: (distKm: Double, durSec: Long, title: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var distText  by remember { mutableStateOf("%.2f".format(run.distanceKm)) }
    var minText   by remember { mutableStateOf((run.durationSec / 60).toString()) }
    var secText   by remember { mutableStateOf((run.durationSec % 60).toString()) }
    var titleText by remember { mutableStateOf(run.title ?: "") }

    val allOk = distText.toDoubleOrNull()?.let { it > 0 } == true &&
                minText.toIntOrNull()?.let { it >= 0 } == true &&
                secText.toIntOrNull()?.let { it in 0..59 } == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("세션 편집", fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${run.date.monthValue}월 ${run.date.dayOfMonth}일 \u00b7 삼성 헬스 원본은 그대로 유지돼요.",
                    fontSize = 11.sp, color = Color.Gray,
                )
                OutlinedTextField(
                    value = distText,
                    onValueChange = { distText = it },
                    label = { Text("거리 (km)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minText,
                        onValueChange = { v -> minText = v.filter { it.isDigit() } },
                        label = { Text("시간 (분)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = secText,
                        onValueChange = { v -> secText = v.filter { it.isDigit() }.take(2) },
                        label = { Text("초 (0~59)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    label = { Text("메모 (선택)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val d = distText.toDoubleOrNull() ?: return@TextButton
                    val dur = (minText.toLongOrNull() ?: 0L) * 60 + (secText.toLongOrNull() ?: 0L)
                    onConfirm(d, dur, titleText.takeIf { it.isNotBlank() })
                },
                enabled = allOk,
            ) { Text("저장", color = RunColors.Violet) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
    )
}
