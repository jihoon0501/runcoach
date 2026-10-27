# ── RunLog 릴리스 빌드용 ProGuard / R8 규칙 ──
# app/proguard-rules.pro 에 복사해서 쓰세요.
# 릴리스 빌드로 설치한 뒤 '삼성 헬스 연결 → 동기화 → 새 계획 → 캘린더 공유'를 꼭 한 번씩 눌러서 확인하세요.

# 삼성 헬스 Data SDK (내부에서 리플렉션 사용)
-keep class com.samsung.android.sdk.health.data.** { *; }
-dontwarn com.samsung.android.sdk.health.data.**

# Gson (삼성 SDK 가 사용)
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod

# Parcelable / Kotlin parcelize
-keep class * implements android.os.Parcelable { *; }
-keepclassmembers class * implements android.os.Parcelable { public static final ** CREATOR; }
-keepclassmembers class kotlin.Metadata { *; }

# Kotlin 코루틴
-dontwarn kotlinx.coroutines.**

# Health Connect (HealthRepo)
-keep class androidx.health.connect.client.** { *; }
-dontwarn androidx.health.connect.**

# 매니페스트에 등록된 수신기
-keep class io.github.jihoon0501.runcoach.ReminderReceiver { *; }
