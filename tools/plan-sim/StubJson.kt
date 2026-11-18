// 시뮬레이션 전용: org.json을 최소 기능으로 다시 구현했어요 (앱에는 들어가지 않아요)
package org.json

class JSONException(m: String) : Exception(m)

class JSONArray() {
    val items = ArrayList<Any?>()
    constructor(c: Collection<*>) : this() { items.addAll(c) }
    fun length(): Int = items.size
    fun getString(i: Int): String = items[i].toString()
    fun getJSONObject(i: Int): JSONObject = items[i] as JSONObject
    fun optJSONObject(i: Int): JSONObject? = items.getOrNull(i) as? JSONObject
    fun put(v: Any?): JSONArray { items.add(v); return this }
    override fun toString(): String = items.joinToString(",", "[", "]") { JSONObject.enc(it) }
}

class JSONObject() {
    val map = LinkedHashMap<String, Any?>()
    constructor(s: String) : this() {
        val o = Parser(s).value() as? JSONObject ?: throw JSONException("not an object")
        map.putAll(o.map)
    }
    fun put(k: String, v: Any?): JSONObject { map[k] = v; return this }
    fun optString(k: String): String = optString(k, "")
    fun optString(k: String, d: String): String = map[k]?.toString() ?: d
    fun optInt(k: String): Int = optInt(k, 0)
    fun optInt(k: String, d: Int): Int = (map[k] as? Number)?.toInt() ?: d
    fun optDouble(k: String): Double = optDouble(k, 0.0)
    fun optDouble(k: String, d: Double): Double = (map[k] as? Number)?.toDouble() ?: d
    fun optJSONArray(k: String): JSONArray? = map[k] as? JSONArray
    fun getString(k: String): String = map[k]?.toString() ?: throw JSONException("No value for $k")
    override fun toString(): String = map.entries.joinToString(",", "{", "}") { "\"${esc(it.key)}\":${enc(it.value)}" }
    companion object {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        fun enc(v: Any?): String = when (v) {
            null -> "null"
            is String -> "\"${esc(v)}\""
            else -> v.toString()
        }
    }
}

private class Parser(val s: String) {
    var i = 0
    fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    fun value(): Any? {
        ws()
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> { i += 4; true }
            'f' -> { i += 5; false }
            'n' -> { i += 4; null }
            else -> { if (c == '-' || c.isDigit()) num() else throw JSONException("bad char $c at $i") }
        }
    }
    fun obj(): JSONObject {
        val o = JSONObject(); i++; ws()
        if (s[i] == '}') { i++; return o }
        while (true) {
            ws(); val k = str(); ws(); i++ // ':'
            o.put(k, value()); ws()
            if (s[i] == ',') { i++; continue }
            i++; return o
        }
    }
    fun arr(): JSONArray {
        val a = JSONArray(); i++; ws()
        if (s[i] == ']') { i++; return a }
        while (true) {
            a.put(value()); ws()
            if (s[i] == ',') { i++; continue }
            i++; return a
        }
    }
    fun str(): String {
        val sb = StringBuilder(); i++
        while (s[i] != '"') {
            if (s[i] == '\\') { i++; sb.append(when (s[i]) { 'n' -> '\n'; else -> s[i] }) } else sb.append(s[i])
            i++
        }
        i++; return sb.toString()
    }
    fun num(): Number {
        val st = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        val t = s.substring(st, i)
        return if (t.contains('.') || t.contains('e') || t.contains('E')) t.toDouble() else t.toLong()
    }
}
