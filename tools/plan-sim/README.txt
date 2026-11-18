훈련 계획 엔진(PlanEngine.kt) 시뮬레이션
실행: bash tools/plan-sim/run.sh   (kotlinc 2.x + JDK 17 이상 필요, Windows는 Git Bash나 WSL)
무엇을 보나요
 A 계획 → 계획한 훈련을 소화 → 4주 뒤 다시 만든 계획 (실행 게이트, 단계 상승, 페이스 보정)
 B 인터벌·템포를 건너뛴 경우 (단계 하향, 실력 기준 페이스의 약점)
 C 컨디션이 나쁠 때 블록 중간 조정, 다시 점검, "원래대로"
 D AI 코치 대화의 계획 변경 (바꾸기, 추가, 쉬기, 되돌리기)
 E 크로스 트레이닝 부하가 피로도에 미치는 영향
 G 하프 목표의 긴 반복
Stub*.kt는 시험 전용 가짜 정의예요(Compose Color, org.json, Readiness 등). 앱에는 들어가지 않아요.
실력 기준 페이스를 새 방식으로 바꿔 비교하려면 PlanEngine.kt의 USE_NEW_ANCHOR를 true로 바꾼 사본으로 실행하세요.
