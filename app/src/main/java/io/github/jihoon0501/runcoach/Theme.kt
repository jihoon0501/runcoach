package io.github.jihoon0501.runcoach

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object RunColors {
    val Bg = Color(0xFF0A0E1A)
    val BgTop = Color(0xFF1B1446)
    val Card = Color(0xFF151B2E)
    val Lime = Color(0xFFC8FF3E)
    val Violet = Color(0xFF8B5CF6)
    val Pink = Color(0xFFFF5C8A)
    val Orange = Color(0xFFFF9F43)
    val Blue = Color(0xFF4DA6FF)
    val Green = Color(0xFF34D399)
    val TextDim = Color(0xFF9AA4BF)
}

private val scheme = darkColorScheme(
    primary = RunColors.Lime,
    onPrimary = RunColors.Bg,
    secondary = RunColors.Violet,
    background = RunColors.Bg,
    surface = RunColors.Card,
    onSurface = Color.White,
    onBackground = Color.White,
    onSurfaceVariant = RunColors.TextDim,
)

val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
    Font(R.font.pretendard_extrabold, FontWeight.ExtraBold),
)

private val base = Typography()

/** 모든 텍스트 스타일에 Pretendard 적용 (Text에서 스타일을 지정하지 않아도 bodyLarge가 기본으로 쓰임) */
private val typography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Pretendard),
    displayMedium = base.displayMedium.copy(fontFamily = Pretendard),
    displaySmall = base.displaySmall.copy(fontFamily = Pretendard),
    headlineLarge = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp),
    headlineMedium = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 17.sp),
    headlineSmall = base.headlineSmall.copy(fontFamily = Pretendard, fontSize = 15.sp),
    titleLarge = base.titleLarge.copy(fontFamily = Pretendard, fontSize = 17.sp),
    titleMedium = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
    titleSmall = base.titleSmall.copy(fontFamily = Pretendard, fontSize = 12.sp),
    bodyLarge = TextStyle(fontFamily = Pretendard, fontSize = 13.sp),
    bodyMedium = TextStyle(fontFamily = Pretendard, fontSize = 12.sp),
    bodySmall = TextStyle(fontFamily = Pretendard, fontSize = 11.sp),
    labelLarge = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelMedium = TextStyle(fontFamily = Pretendard, fontSize = 11.sp),
    labelSmall = TextStyle(fontFamily = Pretendard, fontSize = 10.sp),
)

@Composable
fun RunLogAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
