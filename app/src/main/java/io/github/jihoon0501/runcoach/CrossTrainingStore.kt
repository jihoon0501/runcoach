package io.github.jihoon0501.runcoach

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 크로스 트레이닝 세션을 SharedPreferences에 JSON으로 저장/로드한다.
 * 키: "cross_sessions"  값: JSONArray
 */
object CrossTrainingStore {

    private const val PREF = "cross_training"
    private const val KEY  = "sessions"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ── 저장 ───────────────────────────────────────────────────

    fun save(ctx: Context, session: ActivitySession) {
        val arr = loadRaw(ctx)
        arr.put(toJson(session))
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** 모든 크로스 트레이닝 기록 삭제 ("내 데이터 모두 지우기"에서 사용) */
    fun clearAll(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    fun delete(ctx: Context, startMs: Long) {
        val arr = loadRaw(ctx)
        val filtered = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getLong("start") != startMs) filtered.put(o)
        }
        prefs(ctx).edit().putString(KEY, filtered.toString()).apply()
    }

    // ── 로드 ───────────────────────────────────────────────────

    fun load(ctx: Context): List<ActivitySession> {
        val arr = loadRaw(ctx)
        val list = mutableListOf<ActivitySession>()
        for (i in 0 until arr.length()) {
            runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()?.let { list += it }
        }
        return list.sortedByDescending { it.start }
    }

    fun loadByDate(ctx: Context, date: LocalDate): List<ActivitySession> =
        load(ctx).filter { it.date == date }

    fun loadRecent(ctx: Context, days: Int = 30): List<ActivitySession> {
        val since = LocalDate.now().minusDays(days.toLong())
        return load(ctx).filter { it.date >= since }
    }

    // ── 삼성 헬스에서 가져온 세션 병합 (중복 방지) ───────────────

    fun mergeFromSamsung(ctx: Context, sessions: List<ActivitySession>) {
        val existing = load(ctx).filter { it.source == "samsung" }
            .map { it.start.toEpochMilli() }.toSet()
        val newSessions = sessions.filter { it.start.toEpochMilli() !in existing }
        if (newSessions.isEmpty()) return
        val arr = loadRaw(ctx)
        newSessions.forEach { arr.put(toJson(it)) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    // ── JSON 변환 ──────────────────────────────────────────────

    private fun toJson(s: ActivitySession) = JSONObject().apply {
        put("type",        s.type.name)
        put("start",       s.start.toEpochMilli())
        put("duration",    s.durationSec)
        put("distanceKm",  s.distanceKm)
        put("calories",    s.calories ?: JSONObject.NULL)
        put("avgHr",       s.avgHr    ?: JSONObject.NULL)
        put("sets",        s.sets)
        put("reps",        s.reps)
        put("note",        s.note)
        put("source",      s.source)
    }

    private fun fromJson(o: JSONObject) = ActivitySession(
        type        = runCatching { ActivityType.valueOf(o.getString("type")) }
                          .getOrDefault(ActivityType.CUSTOM),
        start       = Instant.ofEpochMilli(o.getLong("start")),
        durationSec = o.getLong("duration"),
        distanceKm  = o.optDouble("distanceKm", 0.0),
        calories    = o.optInt("calories", 0).takeIf { it > 0 },
        avgHr       = o.optInt("avgHr", 0).takeIf { it > 0 },
        sets        = o.optInt("sets", 0),
        reps        = o.optInt("reps", 0),
        note        = o.optString("note", ""),
        source      = o.optString("source", "manual"),
    )

    private fun loadRaw(ctx: Context): JSONArray =
        runCatching { JSONArray(prefs(ctx).getString(KEY, "[]") ?: "[]") }
            .getOrDefault(JSONArray())
}
