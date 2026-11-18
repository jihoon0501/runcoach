package io.github.jihoon0501.runcoach

import java.time.LocalDate
import java.time.YearMonth

/*
 * 펫(다마고치형 보상) 규칙. 안드로이드에 의존하지 않는 순수 코틀린이다.
 *
 * 설계 원칙
 *  - 코인은 '습관'에서만 나온다: 계획 훈련 완료, 쉬는 날 잘 쉬기, 회복 걷기, 주·월 목표, 업적.
 *  - 체중·체지방 같은 몸 수치의 증감은 절대 보상하지 않는다 (신체 기록 '남기기'만 보상, 값은 상관없음).
 *  - 코인을 더 얻으려고 더 많이 달릴 이유가 없게, 계획 범위를 넘는 거리는 보상하지 않는다.
 *  - 펫은 죽지도 아파하지도 않는다. 안 달리거나 쉬어도 불이익이 없고, 쉬는 것도 보상한다.
 *  - 놓친 보상은 조용히 사라질 뿐 감점이나 죄책감 문구가 없다. 코인은 현금으로 살 수 없다.
 */

enum class QuestPeriod(val label: String) { DAY("일간"), WEEK("주간"), MONTH("월간"), ONCE("업적") }

class Quest(
    val id: String,
    val period: QuestPeriod,
    val title: String,
    val detail: String,
    val points: Int,
    val achieved: Boolean,
    val claimed: Boolean,
    val claimable: Boolean,
)

/** 앱의 러닝·걷기·계획·신체 기록을 날짜 단위로 요약한 입력 */
class PetInput(
    val today: LocalDate,
    val runKmByDate: Map<LocalDate, Double>,
    val recoveryWalkDates: Set<LocalDate>,
    val plannedRunDates: Set<LocalDate>,        // 계획에 있는 러닝(선택 훈련 제외)
    val doneRunDates: Set<LocalDate>,           // 계획대로 한 날
    val plannedKmByDate: Map<LocalDate, Double>,
    val bodyDates: Set<LocalDate>,              // 신체를 측정한 날 (값은 쓰지 않는다)
    val monthlyGoalKm: Double,                  // 내 기준 월 목표 거리 (계획 범위)
    val activeDaysTarget: Int,                  // 월간 '활동한 날' 목표
    val lifetimeKm: Double,
    val longestRunKm: Double,
)

class PetItem(
    val id: String,
    val name: String,
    val emoji: String,
    val slot: String,
    val price: Int,
    val value: Int = 0,         // 먹이: 배부름 증가량
    val minLevel: Int = 1,
)

object PetCatalog {
    const val FOOD = "FOOD"
    const val HAT = "HAT"
    const val FACE = "FACE"
    const val NECK = "NECK"
    const val FEET = "FEET"
    const val WALL = "WALL"
    const val FLOOR = "FLOOR"
    const val FURN = "FURN"

    const val MAX_FURNITURE = 3

    val items: List<PetItem> = listOf(
        PetItem("food_water", "물", "💧", FOOD, 2, 10),
        PetItem("food_apple", "사과", "🍎", FOOD, 5, 25),
        PetItem("food_carrot", "당근", "🥕", FOOD, 5, 25),
        PetItem("food_bento", "도시락", "🍱", FOOD, 12, 60),
        PetItem("food_cake", "간식 케이크", "🍰", FOOD, 20, 100, 2),

        PetItem("hat_cap", "러닝캡", "🧢", HAT, 30),
        PetItem("hat_top", "신사 모자", "🎩", HAT, 80, minLevel = 3),
        PetItem("hat_crown", "왕관", "👑", HAT, 200, minLevel = 6),
        PetItem("face_glasses", "안경", "👓", FACE, 30),
        PetItem("face_sun", "선글라스", "🕶️", FACE, 40),
        PetItem("neck_scarf", "목도리", "🧣", NECK, 35),
        PetItem("neck_ribbon", "리본", "🎀", NECK, 30),
        PetItem("neck_medal", "메달", "🏅", NECK, 60, minLevel = 2),
        PetItem("feet_shoes", "러닝화", "👟", FEET, 50),

        PetItem("wall_default", "기본 벽", "⬜", WALL, 0),
        PetItem("wall_sky", "하늘 벽", "☁️", WALL, 40),
        PetItem("wall_sunset", "노을 벽", "🌅", WALL, 40),
        PetItem("wall_forest", "숲 벽", "🌲", WALL, 60, minLevel = 2),
        PetItem("wall_night", "밤하늘 벽", "🌙", WALL, 60, minLevel = 2),

        PetItem("floor_wood", "마룻바닥", "🟫", FLOOR, 0),
        PetItem("floor_grass", "잔디", "🟩", FLOOR, 30),
        PetItem("floor_sand", "모래", "🟨", FLOOR, 30),
        PetItem("floor_carpet", "카펫", "🟥", FLOOR, 40),

        PetItem("furn_plant", "화분", "🪴", FURN, 25),
        PetItem("furn_frame", "액자", "🖼️", FURN, 40),
        PetItem("furn_books", "책장", "📚", FURN, 60),
        PetItem("furn_sofa", "소파", "🛋️", FURN, 70),
        PetItem("furn_bed", "침대", "🛏️", FURN, 70),
        PetItem("furn_tent", "텐트", "⛺", FURN, 90, minLevel = 3),
        PetItem("furn_trophy", "트로피", "🏆", FURN, 100, minLevel = 4),
    )

    val FREE_DEFAULTS: Set<String> = setOf("wall_default", "floor_wood")

    fun byId(id: String): PetItem? = items.firstOrNull { it.id == id }
    fun bySlot(slot: String): List<PetItem> = items.filter { it.slot == slot }
}

/** 펫의 저장 상태. 코인 잔액 = earned - spent. 성장(레벨)은 지금까지 번 코인(earned)으로만 정해져서 쓴다고 줄지 않는다 */
data class PetState(
    val chosen: Boolean = false,
    val species: String = "PLANT",
    val name: String = "달이",
    val claimed: Set<String> = emptySet(),
    val earned: Int = 0,
    val spent: Int = 0,
    val owned: Set<String> = PetCatalog.FREE_DEFAULTS,
    val equipped: Map<String, String> = mapOf(PetCatalog.WALL to "wall_default", PetCatalog.FLOOR to "floor_wood"),
    val furniture: List<String> = emptyList(),
    val fullness: Int = 60,
    val fedDate: String? = null,
) {
    val balance: Int get() = earned - spent
}

object PetEngine {

    private val LEVEL_AT = intArrayOf(0, 40, 120, 250, 450, 700, 1000, 1400, 1900, 2500)
    private val STAGE_NAMES = arrayOf("아기", "꼬마", "청소년", "어른", "챔피언")

    fun levelOf(earned: Int): Int {
        var lv = 1
        for (i in LEVEL_AT.indices) if (earned >= LEVEL_AT[i]) lv = i + 1
        return lv
    }

    /** 다음 레벨까지 필요한 코인 (최고 레벨이면 null) */
    fun nextLevelAt(earned: Int): Int? = LEVEL_AT.getOrNull(levelOf(earned))

    fun levelStart(earned: Int): Int = LEVEL_AT[levelOf(earned) - 1]

    /** 0~4: 아기, 꼬마, 청소년, 어른, 챔피언 */
    fun stageIndex(level: Int): Int = ((level - 1) / 2).coerceIn(0, 4)
    fun stageName(level: Int): String = STAGE_NAMES[stageIndex(level)]

    /** 마지막으로 먹이를 준 뒤 하루에 15씩 줄어드는 배부름 (0 아래로 내려가도 불이익은 없다) */
    fun fullness(s: PetState, today: LocalDate): Int {
        val fed = s.fedDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return s.fullness.coerceIn(0, 100)
        val days = (today.toEpochDay() - fed.toEpochDay()).coerceAtLeast(0)
        return (s.fullness - 15 * days).toInt().coerceIn(0, 100)
    }

    private fun monday(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value - 1).toLong())

    // ───────────── 퀘스트 ─────────────

    fun quests(inp: PetInput, claimed: Set<String>): List<Quest> {
        val out = ArrayList<Quest>()
        val today = inp.today
        fun add(id: String, p: QuestPeriod, title: String, detail: String, pts: Int, achieved: Boolean, claimableNow: Boolean) {
            val done = claimed.contains(id)
            out += Quest(id, p, title, detail, pts, achieved, done, achieved && claimableNow && !done)
        }
        fun km(d: LocalDate): Double = inp.runKmByDate[d] ?: 0.0
        fun ranRecently(d: LocalDate): Boolean = (1..6).count { km(d.minusDays(it.toLong())) > 0.0 } >= 2

        // 일간: 오늘과 어제
        for (d in listOf(today, today.minusDays(1))) {
            val label = if (d == today) "오늘" else "어제"
            if (d in inp.plannedRunDates) {
                val ok = d in inp.doneRunDates
                add("D:$d:plan", QuestPeriod.DAY, "$label 계획 훈련 완료", if (ok) "계획한 러닝을 했어요" else "계획한 러닝이 있어요", 10, ok, true)
            }
            if (d != today && d !in inp.plannedRunDates && km(d) == 0.0 && ranRecently(d)) {
                add("D:$d:rest", QuestPeriod.DAY, "$label 푹 쉬기", "쉬는 날에 잘 쉬었어요", 5, true, true)
            }
            if (d in inp.recoveryWalkDates) {
                add("D:$d:walk", QuestPeriod.DAY, "$label 회복 걷기", "30분 이상 가볍게 걸었어요", 5, true, true)
            }
        }

        // 주간: 이번 주와 지난 주
        val thisMon = monday(today)
        for (mon in listOf(thisMon, thisMon.minusWeeks(1))) {
            val week = (0..6).map { mon.plusDays(it.toLong()) }
            val over = today.isAfter(week.last())
            val label = if (mon == thisMon) "이번 주" else "지난 주"
            val planned = week.count { it in inp.plannedRunDates }
            val done = week.count { it in inp.doneRunDates }
            if (planned >= 1) {
                val need = if (planned <= 1) 1 else Math.ceil(planned * 0.75).toInt()
                add("W:$mon:count", QuestPeriod.WEEK, "$label 계획 훈련 ${need}회 이상", "${done}/${planned}회 완료", 30, done >= need, true)
            }
            val plannedKm = week.sumOf { inp.plannedKmByDate[it] ?: 0.0 }
            if (plannedKm >= 5.0) {
                val actual = week.sumOf { km(it) }
                val pct = Math.round(actual / plannedKm * 100).toInt()
                val ok = over && actual >= plannedKm * 0.7 && actual <= plannedKm * 1.15
                add(
                    "W:$mon:range", QuestPeriod.WEEK, "$label 계획 거리 안에서",
                    "계획 ${"%.0f".format(plannedKm)}km 중 ${"%.0f".format(actual)}km (${pct}%) · 70~115%면 달성 (주가 끝나면 확인)",
                    20, ok, over,
                )
            }
            if (over) {
                val ranDays = week.count { km(it) > 0.0 }
                var maxConsec = 0
                var cur = 0
                for (d in week) {
                    if (km(d) > 0.0) { cur++; if (cur > maxConsec) maxConsec = cur } else cur = 0
                }
                if (ranDays >= 2) {
                    val ok = ranDays <= 5 && maxConsec <= 3
                    add("W:$mon:rest", QuestPeriod.WEEK, "$label 쉬는 날 지키기", "달리지 않은 날 ${7 - ranDays}일 · 연속 최대 ${maxConsec}일 (연속 3일 이내)", 15, ok, true)
                }
            }
            if (inp.bodyDates.isNotEmpty()) {
                val ok = week.any { it in inp.bodyDates }
                add("W:$mon:body", QuestPeriod.WEEK, "$label 신체 기록 남기기", if (ok) "${label}에 기록을 남겼어요 (값은 상관없어요)" else "주 1회면 충분해요 (값은 상관없어요)", 10, ok, true)
            }
        }

        // 월간: 이번 달과 지난 달
        val thisYm = YearMonth.from(today)
        for (ym in listOf(thisYm, thisYm.minusMonths(1))) {
            val label = if (ym == thisYm) "이번 달" else "지난 달"
            val days = (1..ym.lengthOfMonth()).map { ym.atDay(it) }
            val active = days.count { km(it) > 0.0 || it in inp.recoveryWalkDates }
            add("M:$ym:days", QuestPeriod.MONTH, "$label 활동한 날 ${inp.activeDaysTarget}일", "${active}일 활동 (러닝이나 회복 걷기)", 40, active >= inp.activeDaysTarget, true)
            val monthKm = days.sumOf { km(it) }
            if (inp.monthlyGoalKm >= 20.0) {
                add("M:$ym:km", QuestPeriod.MONTH, "$label ${"%.0f".format(inp.monthlyGoalKm)}km 달리기", "${"%.0f".format(monthKm)}km (내 기준 거리예요, 넘겨도 추가 보상은 없어요)", 50, monthKm >= inp.monthlyGoalKm, true)
            }
            val mondays = days.filter { it.dayOfWeek.value == 1 }
            val goodWeeks = mondays.count { mon ->
                val wk = (0..6).map { mon.plusDays(it.toLong()) }
                val p = wk.count { it in inp.plannedRunDates }
                val dn = wk.count { it in inp.doneRunDates }
                p >= 1 && dn >= (if (p <= 1) 1 else Math.ceil(p * 0.75).toInt())
            }
            if (inp.plannedRunDates.isNotEmpty()) {
                add("M:$ym:weeks", QuestPeriod.MONTH, "$label 3주 이상 계획 달성", "${goodWeeks}주 달성", 60, goodWeeks >= 3, true)
            }
        }

        // 업적: 한 번만
        val any = inp.lifetimeKm > 0.0
        add("O:first_run", QuestPeriod.ONCE, "첫 러닝 기록", "러닝 기록이 생겼어요", 20, any, true)
        add("O:first_10k", QuestPeriod.ONCE, "처음으로 10km", "최장 ${"%.1f".format(inp.longestRunKm)}km", 50, inp.longestRunKm >= 10.0, true)
        add("O:half", QuestPeriod.ONCE, "처음으로 하프(21.1km)", "최장 ${"%.1f".format(inp.longestRunKm)}km", 100, inp.longestRunKm >= 21.0975, true)
        for ((km, pts) in listOf(100 to 50, 300 to 100, 500 to 150, 1000 to 300)) {
            add("O:km_$km", QuestPeriod.ONCE, "누적 ${km}km", "${"%.0f".format(inp.lifetimeKm)}km", pts, inp.lifetimeKm >= km, true)
        }
        if (inp.bodyDates.isNotEmpty()) add("O:body_first", QuestPeriod.ONCE, "첫 신체 기록", "기록을 남기기 시작했어요", 10, true, true)

        return out
    }

    // ───────────── 상태 변경 ─────────────

    /** 퀘스트를 받는다. 받을 수 없으면 null */
    fun claim(s: PetState, q: Quest): PetState? {
        if (!q.claimable || q.id in s.claimed) return null
        return s.copy(claimed = s.claimed + q.id, earned = s.earned + q.points)
    }

    fun claimAll(s: PetState, quests: List<Quest>): Pair<PetState, Int> {
        var cur = s
        var total = 0
        for (q in quests) {
            val n = claim(cur, q) ?: continue
            cur = n
            total += q.points
        }
        return cur to total
    }

    fun canBuy(s: PetState, it: PetItem): Boolean =
        s.balance >= it.price && levelOf(s.earned) >= it.minLevel && (it.slot == PetCatalog.FOOD || it.id !in s.owned)

    /** 먹이를 준다 (코인을 쓰고 배부름이 오른다). 살 수 없으면 null */
    fun feed(s: PetState, it: PetItem, today: LocalDate): PetState? {
        if (it.slot != PetCatalog.FOOD || !canBuy(s, it)) return null
        val f = (fullness(s, today) + it.value).coerceAtMost(100)
        return s.copy(spent = s.spent + it.price, fullness = f, fedDate = today.toString())
    }

    /** 꾸미기 물건을 산다. 사면 바로 착용한다 */
    fun buy(s: PetState, it: PetItem): PetState? {
        if (it.slot == PetCatalog.FOOD || !canBuy(s, it)) return null
        return equip(s.copy(spent = s.spent + it.price, owned = s.owned + it.id), it)
    }

    fun equip(s: PetState, it: PetItem): PetState {
        if (it.id !in s.owned) return s
        return when (it.slot) {
            PetCatalog.FURN -> {
                if (it.id in s.furniture) s
                else s.copy(furniture = (s.furniture + it.id).takeLast(PetCatalog.MAX_FURNITURE))
            }
            PetCatalog.FOOD -> s
            else -> s.copy(equipped = s.equipped + (it.slot to it.id))
        }
    }

    fun unequip(s: PetState, it: PetItem): PetState = when (it.slot) {
        PetCatalog.FURN -> s.copy(furniture = s.furniture - it.id)
        PetCatalog.WALL, PetCatalog.FLOOR -> s    // 벽과 바닥은 비울 수 없고 다른 것으로 바꾼다
        PetCatalog.FOOD -> s
        else -> s.copy(equipped = s.equipped - it.slot)
    }

    // ───────────── 한마디 ─────────────

    /** 펫이 건네는 말. 어떤 경우에도 죄책감을 주는 말은 하지 않는다 */
    fun message(todayPlanned: Boolean, todayDone: Boolean, fullness: Int, hasRunBefore: Boolean): String = when {
        !hasRunBefore -> "처음 만났네요! 러닝 기록이 생기면 같이 자라요."
        todayDone -> "오늘 훈련 완료! 정말 멋져요 🎉"
        todayPlanned -> "오늘은 달리는 날이에요. 몸이 괜찮을 때 천천히 시작해요!"
        fullness < 20 -> "배가 조금 고파요. 코인으로 먹이를 줄 수 있어요 (안 줘도 괜찮아요)."
        else -> "오늘은 쉬는 날이래요. 푹 쉬는 것도 훈련이에요 😴"
    }
}
