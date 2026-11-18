package io.github.jihoon0501.runcoach

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** 펫 상태를 폰(앱 설정 저장소)에 저장하고 읽는다. 이 폰 안에서만 쓰고, '내 데이터 모두 지우기'로 함께 지워진다. */
class PetStore(context: Context) {

    private val prefs = Prefs(context.applicationContext)

    fun load(): PetState {
        val text = prefs.petJson
        if (text.isBlank()) return PetState()
        return try {
            val o = JSONObject(text)
            fun strings(k: String): List<String> {
                val a = o.optJSONArray(k) ?: return emptyList()
                return (0 until a.length()).map { a.getString(it) }
            }
            val equipped = HashMap<String, String>()
            o.optJSONObject("equipped")?.let { e ->
                val keys = e.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    equipped[k] = e.getString(k)
                }
            }
            if (equipped[PetCatalog.WALL] == null) equipped[PetCatalog.WALL] = "wall_default"
            if (equipped[PetCatalog.FLOOR] == null) equipped[PetCatalog.FLOOR] = "floor_wood"
            PetState(
                chosen = o.optBoolean("chosen", false),
                species = o.optString("species", "PLANT"),
                name = o.optString("name", "달이"),
                claimed = strings("claimed").toSet(),
                earned = o.optInt("earned", 0),
                spent = o.optInt("spent", 0),
                owned = strings("owned").toSet() + PetCatalog.FREE_DEFAULTS,
                equipped = equipped,
                furniture = strings("furniture"),
                fullness = o.optInt("fullness", 60),
                fedDate = if (o.has("fedDate") && !o.isNull("fedDate")) o.getString("fedDate") else null,
            )
        } catch (e: Exception) {
            PetState()
        }
    }

    fun save(s: PetState) {
        val o = JSONObject().apply {
            put("chosen", s.chosen)
            put("species", s.species)
            put("name", s.name)
            put("claimed", JSONArray(s.claimed.toList()))
            put("earned", s.earned)
            put("spent", s.spent)
            put("owned", JSONArray(s.owned.toList()))
            put("equipped", JSONObject().also { e -> s.equipped.forEach { (k, v) -> e.put(k, v) } })
            put("furniture", JSONArray(s.furniture))
            put("fullness", s.fullness)
            put("fedDate", s.fedDate ?: JSONObject.NULL)
        }
        prefs.petJson = o.toString()
    }
}

/** 앱의 러닝·걷기·계획·신체 기록을 펫 규칙이 쓰는 날짜별 요약([PetInput])으로 바꾼다 */
object PetInputBuilder {
    fun build(
        plan: List<PlanItem>,
        runs: List<RunSession>,
        walks: List<WalkSession>,
        bodyDates: Set<LocalDate>,
        today: LocalDate,
    ): PetInput {
        val required = plan.filter { !it.optional }
        val matches = PlanEngine.match(plan, runs)
        val plannedKm = required.groupBy { it.date }.mapValues { (_, v) -> v.sumOf { it.km } }
        val doneDates = required.filter { matches.containsKey(it.id) }.map { it.date }.toSet()
        val runKm = runs.groupBy { it.date }.mapValues { (_, v) -> v.sumOf { it.distanceKm } }
        val recovery = PlanEngine.walkDays(walks).filter { it.value.recovery }.keys
        val weeklyBase = PlanEngine.baseKm(runs, today)
        val monthlyGoal = Math.round(weeklyBase * 4 * 0.85).toDouble()
        return PetInput(
            today = today,
            runKmByDate = runKm,
            recoveryWalkDates = recovery,
            plannedRunDates = plannedKm.keys,
            doneRunDates = doneDates,
            plannedKmByDate = plannedKm,
            bodyDates = bodyDates,
            monthlyGoalKm = monthlyGoal,
            activeDaysTarget = maxOf(8, UserProfile.RUN_DAYS * 3),
            lifetimeKm = runs.sumOf { it.distanceKm },
            longestRunKm = runs.maxOfOrNull { it.distanceKm } ?: 0.0,
        )
    }
}
