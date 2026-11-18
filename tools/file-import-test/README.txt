파일 가져오기(FIT/GPX/TCX) 해석 코드를 안드로이드 없이 시험하는 도구
- gen.py: 가짜 FIT·GPX·TCX 파일을 data 폴더에 만든다 (준비운동 걷기 → 본 운동 → 일시정지 → 조깅 → 본 운동 → 마무리 걷기)
- TestMain.kt: 파일을 읽어 종목, 거리, 심박, 케이던스, 구간, 1km 구간을 출력한다
- 컴파일: kotlinc DataStub.kt RunAnalyzer.kt ActivityFiles.kt ActivityConvert.kt TestMain.kt -include-runtime -d test.jar
  (DataStub.kt 는 Data.kt 에서 SegRole 부터 RunSession 까지만 복사한 파일. 화면 라이브러리(Color)에 의존하는 부분을 뺀 것)
