인터벌 구간 분석(RunAnalyzer.kt) 시험
- Synth.kt: 속도 기록 생성기 + 사용자의 실제 인터벌(준비 10분, 4분35초 x 5회, 회복 2분, 마무리 10분) 재현
- Compare.kt: 이전 방식(OldRunAnalyzer)과 새 방식을 비교. 인터벌이 아닌 운동은 결과가 예전과 같은지도 확인
- 컴파일: kotlinc DataStub.kt OldRunAnalyzer.kt RunAnalyzer.kt Synth.kt Compare.kt -include-runtime -d compare.jar
  (DataStub.kt: Data.kt 에서 SegRole 부터 RunSession 까지 복사한 파일, OldRunAnalyzer.kt: 고치기 전 RunAnalyzer.kt 를 이름만 바꾼 사본)
