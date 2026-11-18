package io.github.jihoon0501.runcoach

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 코치 대화 기록을 이 폰 안(SharedPreferences)에 저장한다.
 * 최근 [MAX]개 메시지만 보관하고, 환영 인사는 저장하지 않는다.
 */
object ChatStore {

    private const val PREF = "chat_history"
    private const val KEY  = "messages"
    private const val MAX  = 60

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<AiCoach.ChatMessage> {
        val arr = runCatching { JSONArray(prefs(ctx).getString(KEY, "[]") ?: "[]") }
            .getOrDefault(JSONArray())
        val list = mutableListOf<AiCoach.ChatMessage>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val role = o.optString("r")
            val text = o.optString("t")
            if ((role == "user" || role == "assistant") && text.isNotBlank()) {
                list += AiCoach.ChatMessage(role, text)
            }
        }
        return list
    }

    fun save(ctx: Context, messages: List<AiCoach.ChatMessage>) {
        val arr = JSONArray()
        messages.takeLast(MAX).forEach { m ->
            arr.put(JSONObject().put("r", m.role).put("t", m.text))
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}
