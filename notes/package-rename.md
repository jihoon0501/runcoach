# 패키지명 확정과 변경

> **확정: `io.github.jihoon0501.runcoach`** (GitHub 사용자 `jihoon0501`, 저장소 `runcoach`)
> 프로젝트를 통째로 복사해 둔 뒤, 프로젝트 루트에서 아래 순서로 실행하세요.
> ```
> python rename_package.py com.jihoon.runlog io.github.jihoon0501.runcoach --dry
> python rename_package.py com.jihoon.runlog io.github.jihoon0501.runcoach
> ```

## 왜 지금 정해야 하나
패키지명은 **Play 스토어에 한 번 올리면 바꿀 수 없고**, 삼성 헬스 등록도 패키지명에 묶여요. 등록 후에 바꾸면 다시 등록해야 해요.

## 정하는 기준
- 형식: 소문자, 숫자, 밑줄, 점으로 구분된 2단계 이상 (예: `com.이름.앱이름`)
- Play 전체에서 **유일**해야 해요 (도메인 소유 검증은 없어요). 가능하면 내가 쓸 수 있는 도메인/계정 이름을 써요.
- 앱 이름(RunLog)과 달라도 돼요. 사용자에게 보이지 않아요.
- **개인 이름이 들어가도 괜찮은지** 생각하세요. 패키지명은 스토어 주소(`play.google.com/store/apps/details?id=...`)에 보여요.

## 선택지
- **A. 지금 이름 유지** `com.jihoon.runlog`: 코드 변경 없음, 가장 빠름. 이름이 스토어 주소에 보여요.
- **B. 새 이름으로 변경**: 브랜드 중립 이름을 쓰고 싶을 때. 아래 방법으로 한 번에 바꿔요.

## 변경 방법 1: Android Studio (권장)
1. Project 창을 **Android** 보기에서 **Project** 보기로 바꾸기
2. `app/src/main/java/com/jihoon/runlog` 에서 `runlog` 폴더 우클릭 → Refactor → **Rename** → "Rename package" 선택 → 새 이름 입력 (폴더가 여러 단계면 `jihoon`도 같은 방식으로)
3. `app/build.gradle.kts`의 `namespace`와 `applicationId`를 새 패키지명으로
4. 알림 액션 문자열(`Notifier.kt`의 `ACTION`)과 `AndroidManifest.xml`의 `DAILY_REMINDER`, ProGuard 규칙(`proguard-rules.pro`의 `ReminderReceiver`)에 이전 패키지명이 남았는지 검색(Ctrl+Shift+F)
5. Build → Clean Project → 다시 빌드

## 변경 방법 2: 스크립트 (파이썬이 있을 때)
프로젝트를 통째로 복사해 둔 뒤, 프로젝트 루트에서:
```
python rename_package.py com.jihoon.runlog 새.패키지.이름 --dry     # 바뀔 내용 미리 보기
python rename_package.py com.jihoon.runlog 새.패키지.이름           # 실제 변경
```
소스의 package/import, gradle의 namespace·applicationId, 매니페스트, 알림 액션, ProGuard 규칙, 폴더 위치를 한 번에 바꿔요. (임시 프로젝트에서 미리 보기와 실제 변경, 잘못된 이름 거부를 시험했어요.)

## 바꾼 뒤 확인
- 앱이 실행되고 첫 실행 안내가 나오는지 (패키지가 바뀌면 **새 앱**으로 설치돼서 저장된 데이터가 이어지지 않아요)
- 삼성 헬스 권한 요청이 되는지 (개발자 모드는 패키지명과 상관없이 동작해요)
- 설정 → 앱 정보 → 버전 5번 누르기 → 패키지명이 새 이름으로 나오는지
