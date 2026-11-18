import io.github.jihoon0501.runcoach.*
import java.time.LocalDate

var fails = 0
fun check(name: String, cond: Boolean, extra: String = "") {
    println((if (cond) "OK   " else "FAIL ") + name + (if (extra.isNotEmpty()) "  [$extra]" else ""))
    if (!cond) fails++
}
fun d(s: String) = LocalDate.parse(s)

fun input(
    today: String, runs: Map<String, Double>, planned: Map<String, Double>, done: Set<String>,
    walks: Set<String> = emptySet(), body: Set<String> = emptySet(), goalKm: Double = 80.0, life: Double = 300.0, longest: Double = 12.0,
) = PetInput(
    today = d(today),
    runKmByDate = runs.mapKeys { d(it.key) },
    recoveryWalkDates = walks.map { d(it) }.toSet(),
    plannedRunDates = planned.keys.map { d(it) }.toSet(),
    doneRunDates = done.map { d(it) }.toSet(),
    plannedKmByDate = planned.mapKeys { d(it.key) },
    bodyDates = body.map { d(it) }.toSet(),
    monthlyGoalKm = goalKm, activeDaysTarget = 12, lifetimeKm = life, longestRunKm = longest,
)

fun main() {
    // 오늘 2026-11-04(수). 지난 주(10/26 월~11/1 일): 계획 4회 중 3회, 거리 28/30km. 이번 주: 월 11/2 완료, 수 11/4 계획(아직)
    val planned = mapOf("2026-10-27" to 8.0, "2026-10-29" to 6.0, "2026-10-31" to 10.0, "2026-11-01" to 6.0, "2026-11-02" to 6.0, "2026-11-04" to 8.0)
    val runs = mapOf("2026-10-27" to 8.0, "2026-10-29" to 6.0, "2026-10-31" to 14.0, "2026-11-02" to 6.0)
    val done = setOf("2026-10-27", "2026-10-29", "2026-10-31", "2026-11-02")
    val inp = input("2026-11-04", runs, planned, done, walks = setOf("2026-11-03"), body = setOf("2026-10-30"))
    val qs = PetEngine.quests(inp, emptySet())
    qs.forEach { println("   ${it.period.label} ${it.id} ${it.title} (+${it.points}) 달성=${it.achieved} 받기=${it.claimable} | ${it.detail}") }

    fun q(id: String) = qs.firstOrNull { it.id == id }
    check("오늘 계획이 아직이면 받을 수 없음", q("D:2026-11-04:plan")?.claimable == false)
    check("어제 회복 걷기는 받을 수 있음", q("D:2026-11-03:walk")?.claimable == true)
    check("어제 푹 쉬기는 받을 수 있음 (최근 달린 날 많음)", q("D:2026-11-03:rest")?.claimable == true)
    check("지난 주 계획 훈련 달성 (6회 중 ... 4개 중 3개)", q("W:2026-10-26:count")?.claimable == true)
    check("지난 주 계획 거리 안에서 (28/30km)", q("W:2026-10-26:range")?.claimable == true, q("W:2026-10-26:range")?.detail ?: "")
    check("이번 주 거리 퀘스트는 주가 끝나기 전엔 받을 수 없음", q("W:2026-11-02:range")?.claimable == false)
    check("신체 기록 습관은 값과 상관없이 받을 수 있음", q("W:2026-10-26:body")?.claimable == true)

    // 중복 받기 방지와 잔액
    var s = PetState(chosen = true)
    val (s2, total) = PetEngine.claimAll(s, qs)
    check("모두 받기 합계가 받을 수 있는 퀘스트 점수의 합과 같음", total == qs.filter { it.claimable }.sumOf { it.points }, "total=$total")
    check("받은 만큼 코인이 늘고 잔액=획득", s2.balance == total && s2.earned == total)
    val (s3, again) = PetEngine.claimAll(s2, PetEngine.quests(inp, s2.claimed))
    check("같은 보상은 두 번 받을 수 없음", again == 0 && s3.earned == s2.earned)

    // 과훈련은 보상하지 않는다: 계획 30km인데 48km(160%), 6일 연속 달리기
    val plannedB = mapOf("2026-10-26" to 10.0, "2026-10-28" to 10.0, "2026-10-30" to 10.0)
    val runsB = (26..31).associate { "2026-10-$it" to 8.0 }
    val inpB = input("2026-11-03", runsB, plannedB, plannedB.keys)
    val qb = PetEngine.quests(inpB, emptySet())
    check("계획의 160%를 달려도 거리 보상 없음", qb.first { it.id == "W:2026-10-26:range" }.claimable.not())
    check("6일 연속 달리면 쉬는 날 보상 없음", qb.first { it.id == "W:2026-10-26:rest" }.claimable.not())

    // 어뷰징 방지: 최근에 안 달렸으면 '쉬는 날' 보상을 주지 않는다
    val inpC = input("2026-11-10", emptyMap(), emptyMap(), emptySet(), life = 0.0, longest = 0.0)
    check("안 달리는 사람에게 쉬기 보상을 주지 않음", PetEngine.quests(inpC, emptySet()).none { it.id.contains(":rest") })
    check("기록이 없으면 업적도 없음", PetEngine.quests(inpC, emptySet()).none { it.claimable })

    // 성장과 상점
    check("레벨: 0→1, 40→2, 120→3, 2500→10", PetEngine.levelOf(0) == 1 && PetEngine.levelOf(40) == 2 && PetEngine.levelOf(120) == 3 && PetEngine.levelOf(2500) == 10)
    check("성장 단계: Lv1 아기, Lv3 꼬마, Lv9 챔피언", PetEngine.stageName(1) == "아기" && PetEngine.stageName(3) == "꼬마" && PetEngine.stageName(9) == "챔피언")
    var p = PetState(chosen = true, earned = 100, spent = 0)
    val apple = PetCatalog.byId("food_apple")!!
    val crown = PetCatalog.byId("hat_crown")!!
    check("레벨이 모자라면 왕관을 살 수 없음", !PetEngine.canBuy(p.copy(earned = 300), crown), "Lv=${PetEngine.levelOf(300)}")
    check("코인이 모자라면 못 삼", PetEngine.canBuy(PetState(chosen = true, earned = 3), apple).not())
    val today = d("2026-11-04")
    val fed = PetEngine.feed(p.copy(fullness = 10, fedDate = null), apple, today)!!
    check("먹이: 코인 -5, 배부름 +25", fed.spent == 5 && PetEngine.fullness(fed, today) == 35, "fullness=${PetEngine.fullness(fed, today)}")
    check("배부름은 하루 15씩 줄고 0 아래로 내려가지 않음", PetEngine.fullness(fed, today.plusDays(2)) == 5 && PetEngine.fullness(fed, today.plusDays(10)) == 0)
    check("먹이를 줘도 레벨(성장)은 줄지 않음", PetEngine.levelOf(fed.earned) == PetEngine.levelOf(p.earned))
    val cap = PetCatalog.byId("hat_cap")!!
    val bought = PetEngine.buy(p, cap)!!
    check("사면 바로 착용, 같은 것을 다시 못 삼", bought.equipped["HAT"] == "hat_cap" && !PetEngine.canBuy(bought, cap))
    var f = p.copy(earned = 2000)
    for (id in listOf("furn_plant", "furn_frame", "furn_books", "furn_sofa")) f = PetEngine.buy(f, PetCatalog.byId(id)!!)!!
    check("가구는 최대 3개만 놓임 (가장 최근 3개)", f.furniture == listOf("furn_frame", "furn_books", "furn_sofa"), f.furniture.toString())
    check("벗기면 비워짐 (벽·바닥은 못 비움)", PetEngine.unequip(bought, cap).equipped["HAT"] == null && PetEngine.unequip(p, PetCatalog.byId("wall_default")!!).equipped["WALL"] == "wall_default")

    // 한마디
    check("쉬는 날 한마디는 쉬기를 격려", PetEngine.message(false, false, 80, true).contains("쉬"))
    check("계획 훈련을 못 해도 비난하지 않음", !PetEngine.message(true, false, 80, true).contains("못") && !PetEngine.message(true, false, 80, true).contains("안 "))
    println(if (fails == 0) "\n전체 통과" else "\n실패 $fails 개")
}
