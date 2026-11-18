펫(다마고치형 보상) 보관본 - 앱에는 들어가 있지 않아요 (빌드 대상 아님)

다시 쓰려면
1) PetEngine.kt, PetStore.kt, PetScreen.kt 를 app/src/main/java/io/github/jihoon0501/runcoach/ 로 되돌리기
2) PlanStore.kt 의 Prefs 에 petJson 속성 추가:
     var petJson: String
         get() = sp.getString("pet_json", "") ?: ""
         set(v) = sp.edit().putString("pet_json", v).apply()
3) MainActivity.kt 의 Tab enum 에 PET("펫", Icons.Default.Star), when 에 Tab.PET -> PetScreen(runs, walks, plan, today)
4) '내 데이터 모두 지우기' 안내 문구와 개인정보처리방침에 펫 항목 다시 넣기

PetEngine.kt 는 안드로이드 없이 시험돼요 (PetTest.kt, 퀘스트·중복 방지·과훈련 방지·상점 규칙).
컴파일: kotlinc PetEngine.kt PetTest.kt -include-runtime -d pet.jar && java -jar pet.jar

다시 구성할 때의 원칙: 코인은 습관에서만, 몸 수치 증감은 보상하지 않기, 더 달려도 보상 늘리지 않기,
쉬는 것도 보상하기, 죽음·감점·죄책감 문구 없기, 코인은 현금으로 살 수 없게.
