package io.github.jihoon0501.runcoach

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** AI 평가 결과 (source: "Claude" 또는 "기기 내 코치") */
data class AiResult(val text: String, val source: String)

/** 앱 내부 저장소. 계획, 일정 변경, 알림 설정, API 키, AI 평가 결과를 저장한다. */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("runlog", Context.MODE_PRIVATE)

    // ── 계획 ──
    fun loadPlan(): List<PlanItem>? {
        val s = sp.getString("plan", null) ?: return null
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val tips = o.getJSONArray("tips").let { t -> (0 until t.length()).map { t.getString(it) } }
                PlanItem(
                    date = LocalDate.parse(o.getString("date")),
                    week = o.getString("week"),
                    type = WorkoutType.valueOf(o.getString("type")),
                    title = o.getString("title"),
                    km = o.getDouble("km"),
                    optional = o.getBoolean("optional"),
                    tips = tips,
                    id = o.getString("id"),
                    targetPace = o.optInt("targetPace", 0),
                    reps = o.optInt("reps", 0),
                    rescheduled = o.optBoolean("rescheduled", false),
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    fun savePlan(plan: List<PlanItem>) {
        val arr = JSONArray()
        plan.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("date", p.date.toString())
                put("week", p.week)
                put("type", p.type.name)
                put("title", p.title)
                put("km", p.km)
                put("optional", p.optional)
                put("tips", JSONArray(p.tips))
                put("targetPace", p.targetPace)
                put("reps", p.reps)
                put("rescheduled", p.rescheduled)
            })
        }
        sp.edit().putString("plan", arr.toString()).apply()
    }

    // ── 설정 ──
    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    var reminderOn: Boolean
        get() = sp.getBoolean("reminder_on", true)
        set(v) = sp.edit().putBoolean("reminder_on", v).apply()

    var reminderHour: Int
        get() = sp.getInt("reminder_hour", 18)
        set(v) = sp.edit().putInt("reminder_hour", v).apply()

    var reminderMinute: Int
        get() = sp.getInt("reminder_minute", 0)
        set(v) = sp.edit().putInt("reminder_minute", v).apply()

    var lastPopupDate: String
        get() = sp.getString("last_popup", "") ?: ""
        set(v) = sp.edit().putString("last_popup", v).apply()

    // ── AI 평가 결과 ──
    fun loadAi(): Map<String, AiResult> {
        val s = sp.getString("ai", null) ?: return emptyMap()
        return try {
            val o = JSONObject(s)
            o.keys().asSequence().associateWith { k ->
                val v = o.getJSONObject(k)
                AiResult(v.getString("t"), v.getString("s"))
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveAi(key: String, r: AiResult) {
        val o = try { JSONObject(sp.getString("ai", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        o.put(key, JSONObject().put("t", r.text).put("s", r.source))
        sp.edit().putString("ai", o.toString()).apply()
    }

    // ── 계획 조정 기록 (왜 바꿨는지) ──
    fun loadPlanNote(): PlanNote? {
        val s = sp.getString("plan_note", null) ?: return null
        return try {
            val o = JSONObject(s)
            fun list(k: String): List<String> = o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } }
            PlanNote(
                createdAt = o.getString("createdAt"),
                score = o.getInt("score"),
                level = o.getString("level"),
                facts = list("facts"),
                changes = list("changes"),
                reasons = list("reasons"),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun savePlanNote(n: PlanNote) {
        val o = JSONObject().apply {
            put("createdAt", n.createdAt)
            put("score", n.score)
            put("level", n.level)
            put("facts", JSONArray(n.facts))
            put("changes", JSONArray(n.changes))
            put("reasons", JSONArray(n.reasons))
        }
        sp.edit().putString("plan_note", o.toString()).apply()
    }

    // ── 구글 캘린더 공유 설정 ──
    var calendarId: Long
        get() = sp.getLong("cal_id", 0L)
        set(v) = sp.edit().putLong("cal_id", v).apply()

    var calendarAuto: Boolean
        get() = sp.getBoolean("cal_auto", false)
        set(v) = sp.edit().putBoolean("cal_auto", v).apply()

    var calendarHour: Int
        get() = sp.getInt("cal_hour", 21)
        set(v) = sp.edit().putInt("cal_hour", v).apply()

    var calendarMinute: Int
        get() = sp.getInt("cal_minute", 30)
        set(v) = sp.edit().putInt("cal_minute", v).apply()

    /** 계획 id → 캘린더 일정 id */
    fun loadCalendarMap(): Map<String, Long> {
        val s = sp.getString("cal_map", null) ?: return emptyMap()
        return try {
            val o = JSONObject(s)
            o.keys().asSequence().associateWith { o.getLong(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveCalendarMap(m: Map<String, Long>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        sp.edit().putString("cal_map", o.toString()).apply()
    }

    // ── 내 정보 (첫 실행 화면에서 입력, 이 폰에만 저장) ──
    fun loadProfile(): ProfileData {
        val s = sp.getString("profile", null) ?: return ProfileData()
        return try {
            val o = JSONObject(s)
            ProfileData(
                nickname = o.optString("nickname", ""),
                birthYear = o.optInt("birthYear", 0),
                sex = o.optString("sex", ""),
                heightCm = o.optInt("heightCm", 170),
                weightKg = o.optInt("weightKg", 65),
                maxHr = o.optInt("maxHr", 0),
                goalDistanceKm = o.optDouble("goalDistanceKm", 10.0),
                goalTimeSec = o.optInt("goalTimeSec", 0),
                runDays = o.optInt("runDays", 4),
                weeklyKm = o.optInt("weeklyKm", 15),
                jointCare = o.optBoolean("jointCare", true),
                onboarded = o.optBoolean("onboarded", false),
            )
        } catch (e: Exception) {
            ProfileData()
        }
    }

    fun saveProfile(p: ProfileData) {
        val o = JSONObject().apply {
            put("nickname", p.nickname)
            put("birthYear", p.birthYear)
            put("sex", p.sex)
            put("heightCm", p.heightCm)
            put("weightKg", p.weightKg)
            put("maxHr", p.maxHr)
            put("goalDistanceKm", p.goalDistanceKm)
            put("goalTimeSec", p.goalTimeSec)
            put("runDays", p.runDays)
            put("weeklyKm", p.weeklyKm)
            put("jointCare", p.jointCare)
            put("onboarded", p.onboarded)
        }
        sp.edit().putString("profile", o.toString()).apply()
    }

    /** 모든 로컬 데이터 삭제 (설정의 '내 데이터 모두 지우기'에서 사용) */
    fun clearAll() {
        sp.edit().clear().apply()
    }
}
