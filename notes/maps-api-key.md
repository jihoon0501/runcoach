# Google Maps API 키 설정

경로 화면(지도)은 Google Maps SDK를 쓰고, 키가 없으면 화면이 열리자마자 앱이 종료돼요(Logcat에 `API key not found`).
키는 저장소에 올리지 않아요. 공개 저장소에 올라간 키는 자동으로 수집돼 다른 사람이 쓸 수 있고, 요금이 청구될 수 있어요.

## 1. 키를 `local.properties`에 넣기
프로젝트 루트의 `local.properties`(`.gitignore`에 들어 있어서 올라가지 않아요)에 한 줄을 추가해요.

```
MAPS_API_KEY=발급받은_키
```

## 2. `app/build.gradle.kts`에서 Manifest로 주입하기
이 저장소의 `AndroidManifest.xml`은 키 자리에 `${MAPS_API_KEY}`를 써 둔 상태예요.

```kotlin
import java.util.Properties

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    defaultConfig {
        manifestPlaceholders["MAPS_API_KEY"] = localProps.getProperty("MAPS_API_KEY", "")
    }
}
```

## 3. 키 제한 (권장)
Google Cloud Console → API 및 서비스 → 사용자 인증 정보 → 해당 키에서 설정해요.

- 애플리케이션 제한: **Android 앱** → 패키지명 `io.github.jihoon0501.runcoach` + 서명 SHA-1 (`./gradlew signingReport`로 확인, 디버그 키와 배포 키를 모두 등록)
- API 제한: **Maps SDK for Android**

## 4. 키가 이미 공개됐다면
Cloud Console에서 그 키를 삭제하고 새 키를 발급해요. 저장소 기록에서 지워도 이미 복사된 키는 계속 쓸 수 있어요.
