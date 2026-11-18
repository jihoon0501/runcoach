// 시뮬레이션 전용 가짜 정의: Compose의 Color만 흉내 내요 (앱에는 들어가지 않아요)
package androidx.compose.ui.graphics
class Color(val value: Long) { companion object { val White = Color(0xFFFFFFFF); val Black = Color(0xFF000000); val Transparent = Color(0) } }
