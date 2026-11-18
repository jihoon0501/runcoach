package io.github.jihoon0501.runcoach

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import kotlin.math.roundToInt

object AiCoach {

    /** Claude 모델 이름 (바꾸고 싶으면 이 값만 수정) */
    const val MODEL = "claude-sonnet-5-5"

    private const val SYSTEM =
        "당신은 아마추어 러너를 돕는 친절한 러닝 코치입니다. " +
            "제공된 러닝 기록, 최근 이력, 훈련 계획을 근거로 한국어로 간결하게 평가합니다. " +
            "근거 없는 추측이나 의학적 진단은 하지 않고, 통증이 언급되면 쉬고 전문가와 상담하도록 권합니다. " +
            "마크다운 기호(#, *, |)는 쓰지 말고 일반 텍스트와 줄바꿈, 이모지 정도만 사용하세요."

    private fun mmss(sec: Int): String = "${sec / 60}:${"%02d".format(sec % 60)}"

    private fun efficiency(r: RunSession): Double? {
        val hr = r.avgHr ?: return null
        val pace = r.paceSecPerKm
        if (hr <= 0 || pace <= 0) return null
        return (1000.0 / pace * 60.0) / hr   // 분당 이동 거리(m) / 평균 심박
    }

    // ───────────── 기기 내 평가 (인터넷/키 불필요) ─────────────

    fun local(
        run: RunSession,
        history: List<RunSession>,
        plan: PlanItem?,
        eval: Evaluation?,
        advice: PlanEngine.RecoveryAdvice? = null,
    ): String {
        val good = mutableListOf<String>()
        val bad = mutableListOf<String>()
        val hr = run.avgHr
        val pace = run.paceSecPerKm
        val zone = when {
            hr == null -> "강도 정보 없음"
            hr <= UserProfile.EASY_HR + 3 -> "이지(유산소) 강도"
            hr <= (UserProfile.MAX_HR * 0.90).roundToInt() -> "지속주·템포 강도"
            else -> "고강도"
        }

        // 페이싱
        val sp = run.splits
        if (sp.size >= 4) {
            val half = sp.size / 2
            val first = sp.take(half).average()
            val second = sp.drop(half).average()
            val diff = first - second
            when {
                diff >= 5 -> good += "후반이 ${diff.roundToInt()}초 빨라진 네거티브 스플릿이에요."
                diff <= -10 -> bad += "후반 페이스가 ${(-diff).roundToInt()}초 느려졌어요. 초반 오버페이스를 점검해 보세요."
                else -> good += "구간 페이스가 고르게 유지됐어요."
            }
            val avg = sp.average()
            if (sp[0] < avg - 15) bad += "첫 1km가 평균보다 빨랐어요. 초반은 더 여유 있게 시작하세요."
        }

        // 심박 효율 (최근 기록 대비)
        val effNow = efficiency(run)
        val effHist = history.filter { it.distanceKm >= 5 }.mapNotNull { efficiency(it) }
        if (effNow != null && effHist.size >= 3) {
            val base = effHist.average()
            val pct = (effNow / base - 1.0) * 100.0
            when {
                pct >= 2 -> good += "같은 심박 대비 속도가 최근 평균보다 ${"%.0f".format(pct)}% 좋아졌어요 (효율 향상)."
                pct <= -3 -> bad += "심박 대비 속도 효율이 최근 평균보다 ${"%.0f".format(-pct)}% 낮아요. 피로나 컨디션을 확인하세요."
            }
        }

        // 케이던스
        val cad = run.cadence
        if (cad != null) {
            if (cad >= 170) good += "케이던스 ${cad}spm으로 안정적이에요."
            else if (cad < 160 && pace in 1.0..360.0) bad += "빠른 속도에 비해 케이던스(${cad}spm)가 낮아요. 보폭보다 회전수를 의식해 보세요."
        }

        // 준비운동
        if (run.segments.any { it.role == SegRole.WARMUP }) good += "준비운동 구간을 포함했어요."
        else if ((hr ?: 0) > UserProfile.HARD_HR) bad += "고강도 러닝인데 준비운동 구간이 없어요. 시작 전 5~10분 조깅을 권해요."

        // 회복/부하
        val prevDay = history.any { it.date == run.date.minusDays(1) }
        if (prevDay) bad += "전날도 러닝해서 이틀 연속이에요. " + (if (UserProfile.data.jointCare) "무릎 부담에 주의하세요." else "회복에 신경 쓰세요.")
        val weekStart = run.date.minusDays(6)
        val weekKm = (history + run).filter { it.date in weekStart..run.date }.sumOf { it.distanceKm }
        val prior = history.filter { it.date < weekStart && it.date >= weekStart.minusDays(28) }
        if (prior.isNotEmpty()) {
            val avgWeek = prior.sumOf { it.distanceKm } / 4.0
            if (avgWeek > 0 && weekKm > avgWeek * 1.2) {
                bad += "최근 7일 ${"%.0f".format(weekKm)}km로 평소(${"%.0f".format(avgWeek)}km)보다 ${"%.0f".format((weekKm / avgWeek - 1) * 100)}% 많아요."
            }
        }

        // 계획 대비
        if (eval != null) {
            val line = "계획 대비 ${eval.score}점(${eval.grade}) · ${eval.summary}"
            if (eval.score >= 75) good += line else bad += line
        }

        // 다음 제안
        val next = when {
            advice != null && advice.restDays > 0 ->
                "내일부터 ${advice.restDays}일은 러닝을 쉬세요(${advice.reasons.joinToString("; ")}). " +
                    "그 뒤 ${formatPace(UserProfile.DEFAULT_EASY_PACE.toDouble())} 이지런부터 다시 시작하세요."
            (hr ?: 0) > (UserProfile.MAX_HR * 0.90).roundToInt() || (pace in 1.0..(UserProfile.GOAL_10K_PACE + 30.0)) ->
                "내일은 휴식이나 가벼운 걷기·근력으로 회복하고, 다음 러닝은 ${formatPace(UserProfile.DEFAULT_EASY_PACE.toDouble())} 이지런 6km를 권해요."
            run.distanceKm >= maxOf(8.0, PlanEngine.baseKm(history + run, run.date) * 0.4) -> "롱런 뒤에는 24시간 휴식을 두고, 다음은 짧은 이지런으로 이어가세요."
            hr != null && hr > UserProfile.EASY_HR + 3 -> "다음 러닝은 심박 ${UserProfile.EASY_HR} 이하의 이지런으로 회복에 써 보세요."
            else -> "컨디션이 좋으면 다음 퀄리티 훈련(인터벌·템포)으로 넘어가도 좋아요."
        }

        return buildString {
            append("📌 한줄평\n")
            append("%.1fkm를 평균 %s에 %s%s 달렸어요.\n".format(
                run.distanceKm, formatPace(pace),
                if (hr != null) "심박 ${hr}bpm으로 " else "",
                zone
            ))
            if (good.isNotEmpty()) {
                append("\n✅ 잘한 점\n")
                good.take(4).forEach { append("• $it\n") }
            }
            if (bad.isNotEmpty()) {
                append("\n⚠️ 개선할 점\n")
                bad.take(4).forEach { append("• $it\n") }
            }
            append("\n➡️ 다음 제안\n• $next\n")
            append("\n※ 기록 기반 자동 분석이며 의료 조언이 아니에요. 통증이 있으면 쉬고 전문가와 상담하세요.")
        }
    }

    // ───────────── Claude API 평가 ─────────────

    private fun runLine(r: RunSession): String =
        "${r.date} ${"%.1f".format(r.distanceKm)}km 페이스 ${formatPace(r.paceSecPerKm)} " +
            "심박 ${r.avgHr ?: "-"}/${r.maxHr ?: "-"} 케이던스 ${r.cadence ?: "-"}"

    private fun buildPrompt(run: RunSession, history: List<RunSession>, plan: PlanItem?, eval: Evaluation?, advice: String?): String =
        buildString {
            append("[사용자]\n")
            append(UserProfile.promptSummary())

            append("[평가할 러닝]\n")
            append(runLine(run)).append("\n")
            append("본 운동 ${"%.2f".format(run.mainKm)}km, 총 ${"%.2f".format(run.distanceKm)}km")
            run.vo2Max?.let { append(", VO2max 추정 $it") }
            append("\n")
            if (run.splits.isNotEmpty()) append("1km 구간: ${run.splits.joinToString(" ") { mmss(it) }}\n")
            if (run.segments.isNotEmpty()) {
                append("구성: ")
                append(run.segments.joinToString(" / ") { "${it.label} ${"%.2f".format(it.distanceKm)}km ${formatPace(it.paceSecPerKm)}" })
                append("\n")
            }

            append("\n[최근 러닝 이력]\n")
            history.takeLast(12).forEach { append(runLine(it)).append("\n") }

            if (plan != null) {
                append("\n[그날 추천 계획]\n")
                append("${plan.title} (${plan.type.label}), 계획 ${"%.1f".format(plan.km)}km, 목표 페이스 ${formatPace(PlanEngine.targetPace(plan).toDouble())}\n")
                if (eval != null) append("앱의 규칙 기반 평가: ${eval.score}점 ${eval.grade} / ${eval.points.joinToString("; ")}\n")
            }

            if (advice != null) {
                append("\n[앱의 회복 판단]\n").append(advice).append("\n")
            }

            append("\n[요청]\n")
            if (advice != null) {
                append("'다음 훈련 제안'은 위 '앱의 회복 판단'과 모순되지 않게 쓰세요(휴식이 필요하다고 했으면 휴식을 권하세요).\n")
            }
            append("아래 형식으로 12줄 이내로 답하세요.\n")
            append("한줄평 / 잘한 점 2개 / 개선할 점 2개 / 다음 훈련 제안 1개")
        }

    /**
     * Claude API 공통 호출. 응답의 텍스트 블록만 이어 붙여 돌려준다.
     * Sonnet 5.5는 thinking 설정이 없으면 추론을 켠 채 응답해서, 추론이 max_tokens를 먼저 다 써버릴 수 있다.
     * 그래서 추론을 가장 낮은 단계(between_tools)로 두고, 이 설정을 모델이 거부하면(400) 설정 없이 한도를 늘려 다시 시도한다.
     */
    private suspend fun post(apiKey: String, system: String, prompt: String, maxTokens: Int): String {
        try {
            return doPost(apiKey, system, prompt, maxTokens, thinkingType = "between_tools")
        } catch (e: IllegalStateException) {
            val m = e.message ?: ""
            if (m.startsWith("HTTP 400") && m.contains("thinking", ignoreCase = true)) {
                return doPost(apiKey, system, prompt, maxTokens * 2, thinkingType = null)
            }
            throw e
        }
    }

    private suspend fun doPost(
        apiKey: String,
        system: String,
        prompt: String,
        maxTokens: Int,
        thinkingType: String?,
    ): String = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", maxTokens)
            put("system", system)
            if (thinkingType != null) put("thinking", JSONObject().put("type", thinkingType))
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        }
        val conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 90000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try { JSONObject(text).getJSONObject("error").getString("message") } catch (e: Exception) { text.take(200) }
                throw IllegalStateException("HTTP $code: $msg")
            }
            val root = JSONObject(text)
            val content = root.optJSONArray("content") ?: JSONArray()
            val sb = StringBuilder()
            val types = mutableListOf<String>()
            for (k in 0 until content.length()) {
                val b = content.getJSONObject(k)
                types += b.optString("type")
                if (b.optString("type") == "text") sb.append(b.optString("text"))
            }
            val out = sb.toString().trim()
            if (out.isEmpty()) {
                throw IllegalStateException(
                    "빈 응답 (stop_reason=${root.optString("stop_reason").ifEmpty { "-" }}, " +
                        "내용=${types.joinToString(",").ifEmpty { "없음" }}, " +
                        "모델=${root.optString("model").ifEmpty { "-" }}, 본문=${text.take(160)})"
                )
            }
            out
        } finally {
            conn.disconnect()
        }
    }

    suspend fun claude(
        apiKey: String,
        run: RunSession,
        history: List<RunSession>,
        plan: PlanItem?,
        eval: Evaluation?,
        advice: String? = null,
    ): String = post(apiKey, SYSTEM, buildPrompt(run, history, plan, eval, advice), 4096)

    /** 새 계획을 만든 뒤, 조정이 타당한지 코치 입장에서 검토한 의견 */
    suspend fun claudePlanNote(apiKey: String, note: PlanNote): String {
        val prompt = buildString {
            append("[사용자]\n")
            append(UserProfile.promptSummary())
            append("[앱이 계산한 피로도] ${note.score}/100 (${note.level})\n")
            // 컨디션 줄에는 수면 시간·야간 심박 같은 수치가 들어 있어서, AI에는 점수와 단계만 보낸다
            append("[근거]\n"); note.facts.map { if (it.startsWith("오늘 컨디션")) it.substringBefore(" · ") else it }.forEach { append("- $it\n") }
            append("[계획에서 바꾼 점]\n"); note.changes.forEach { append("- $it\n") }
            append("[바꾼 이유]\n"); note.reasons.forEach { append("- $it\n") }
            append("\n[요청]\n이 조정이 타당한지 코치로서 짧게 검토해 주세요. 동의하는 점 1~2개, 더 조심할 점 2~3개, ")
            append("몸 상태를 확인하는 방법(통증·안정 시 심박·수면 등) 1개를 8줄 이내로 알려주세요.")
        }
        return post(apiKey, SYSTEM, prompt, 4096)
    }
    // ───────────── 채팅 AI ─────────────────────────────────────────

    private const val CHAT_SYSTEM =
        "당신은 아마추어 러너의 개인 AI 러닝 코치입니다. " +
        "제공된 사용자 정보·최근 기록·훈련 계획·피로도를 근거로 대화합니다. " +
        "한국어로 친절하고 간결하게 답하세요. " +
        "피로도가 '높음'이거나 최근 부하가 급증한 상태에서는 인터벌·템포 같은 강한 훈련을 권하지 말고 이지런이나 휴식을 권하세요. " +
        "앱이 컨디션 때문에 낮춘 훈련이 있으면 그 이유를 존중하세요. " +
        "사이클·근력·구기 같은 크로스 트레이닝도 피로에 반영돼 있습니다. 최근 기록을 고려하되, 하체 근력이나 구기 운동 뒤에 강한 훈련을 할지는 사용자가 정하게 하고 제안만 하세요. " +
        "계획 변경은 '오늘' 훈련 하나만 가능합니다. 변경이 적절하다고 판단되면 응답 마지막에 반드시 다음 형식을 추가하세요:\n" +
        "PLAN_CHANGE:{type}:{km}:{reason}\n" +
        "type은 EASY/LONG/INTERVAL/TEMPO/TEST/REST 중 하나(TEST는 10K 기록 측정, REST는 오늘 훈련 쉬기), " +
        "km은 숫자(REST는 0), reason은 한줄 이유. " +
        "INTERVAL·TEMPO·TEST는 앱이 정해진 구성으로 만들어서 km은 참고로만 쓰입니다. " +
        "계획 변경이 불필요하면 PLAN_CHANGE 줄을 쓰지 마세요. " +
        "마크다운 기호(#, *, |, **)는 쓰지 마세요."

    data class ChatMessage(val role: String, val text: String)  // role: "user" | "assistant"

    data class ChatReply(
        val text: String,          // 사용자에게 보여줄 텍스트
        val planChange: PlanChange? = null,  // 계획 변경 제안 (있을 때만)
    )

    data class PlanChange(
        val type: WorkoutType?,   // null이면 오늘 훈련 쉬기(REST)
        val km: Double,
        val reason: String,
    )

    /** 채팅 컨텍스트 — 현재 상황 요약 */
    private fun buildChatContext(
        runs: List<RunSession>,
        plan: List<PlanItem>,
        today: java.time.LocalDate,
        fatigue: Int?,
        cross: List<ActivitySession> = emptyList(),
    ): String = buildString {
        append("[사용자]\n")
        append(UserProfile.promptSummary())
        append("\n[오늘 날짜] $today\n")

        // 오늘 계획
        val todayPlan = plan.firstOrNull { it.date == today }
        if (todayPlan != null) {
            append("[오늘 계획] ${todayPlan.title} (${todayPlan.type.label}), ${todayPlan.km}km")
            if (todayPlan.targetPace > 0)
                append(", 목표 페이스 ${mmss(todayPlan.targetPace)}/km")
            append("\n")
        } else {
            val upcoming = plan.filter { it.date >= today }.minByOrNull { it.date }
            if (upcoming != null)
                append("[다음 계획] ${upcoming.date} ${upcoming.title} ${upcoming.km}km\n")
            else
                append("[계획] 오늘 이후 계획 없음\n")
        }

        // 최근 7일 러닝
        val recent = runs.filter { it.date >= today.minusDays(7) }.sortedByDescending { it.date }
        if (recent.isNotEmpty()) {
            append("\n[최근 7일 러닝]\n")
            recent.forEach { r ->
                append("  ${r.date.monthValue}/${r.date.dayOfMonth} ${"%.1f".format(r.distanceKm)}km " +
                    "${mmss(r.paceSecPerKm.toInt())}/km")
                r.avgHr?.let { append(" HR${it}") }
                append("\n")
            }
        }

        // 최근 4주 주간 볼륨
        val weekKm = runs.filter { it.date >= today.minusDays(28) }.sumOf { it.distanceKm }
        append("[최근 4주 총 거리] ${"%.0f".format(weekKm)}km (주평균 ${"%.0f".format(weekKm / 4)}km)\n")

        // 피로도
        if (fatigue != null) {
            append("[현재 피로도] $fatigue/100 (${if (fatigue >= 70) "높음" else if (fatigue >= 45) "보통" else "낮음"})\n")
        }

        // 최근 7일 크로스 트레이닝 (피로도와 최근 부하에는 이미 반영돼 있다)
        val recentCross = cross.filter { it.date >= today.minusDays(6) && it.date <= today }.sortedByDescending { it.start }
        if (recentCross.isNotEmpty()) {
            val eqKm = recentCross.sumOf { PlanEngine.crossKm(it, runs) }
            append("[최근 7일 크로스 트레이닝] ${recentCross.size}회, 러닝 환산 +${"%.1f".format(eqKm)}km (피로도에 반영됨)\n")
            recentCross.take(6).forEach {
                append("  ${it.date.monthValue}/${it.date.dayOfMonth} ${it.type.label} ${it.durationMin}분")
                if (it.distanceKm > 0) append(" ${"%.1f".format(it.distanceKm)}km")
                if (it.sets > 0) append(" ${it.sets}세트×${it.reps}회")
                append("\n")
            }
        }

        // 앞으로의 계획 (오늘 이후 3개)
        val nextItems = plan.filter { it.date > today }.sortedBy { it.date }.take(3)
        if (nextItems.isNotEmpty()) {
            append("[앞으로 계획] " + nextItems.joinToString(" / ") { "${it.date.monthValue}/${it.date.dayOfMonth} ${it.title}(${it.km}km)" } + "\n")
        }

        // 훈련 단계와 최근 퀄리티 훈련 수행
        val q28 = plan.filter {
            !it.optional && (it.type == WorkoutType.INTERVAL || it.type == WorkoutType.TEMPO) &&
                it.date < today && it.date >= today.minusDays(28)
        }
        if (q28.isNotEmpty()) {
            val qm = PlanEngine.match(q28, runs)
            append("[지난 4주 퀄리티 훈련] ${q28.count { qm.containsKey(it.id) }}/${q28.size}회 수행\n")
        }
        val stageNow = plan.filter {
            (it.type == WorkoutType.INTERVAL || it.type == WorkoutType.TEMPO) && it.date >= today
        }.minByOrNull { it.date }?.stage
        if (stageNow != null) append("[훈련 단계] 점진 상승 단계 $stageNow/5\n")

        // 앱이 컨디션 때문에 낮춘 훈련
        plan.filter { PlanEngine.isAdjusted(it) && it.date >= today }.forEach {
            append("[앱이 낮춘 훈련] ${it.date.monthValue}/${it.date.dayOfMonth} ${it.title}: ${PlanEngine.adjReason(it)}\n")
        }

        // 신체 트렌드
        UserProfile.bodyTrend?.let { append("[신체 정보] $it\n") }
    }

    /** 채팅 1턴 — history는 직전 대화 (최대 10턴) */
    suspend fun chat(
        apiKey: String,
        userMessage: String,
        history: List<ChatMessage>,
        runs: List<RunSession>,
        plan: List<PlanItem>,
        today: java.time.LocalDate,
        fatigue: Int? = null,
        cross: List<ActivitySession> = emptyList(),
    ): ChatReply {
        val ctx = buildChatContext(runs, plan, today, fatigue, cross)
        val systemPrompt = CHAT_SYSTEM + "\n\n" + ctx

        // 멀티턴 messages 구성
        val messages = JSONArray()
        history.takeLast(10).forEach { m ->
            messages.put(JSONObject().put("role", m.role).put("content", m.text))
        }
        messages.put(JSONObject().put("role", "user").put("content", userMessage))

        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 2000)
            .put("system", systemPrompt)
            .put("messages", messages)

        val raw = withContext(Dispatchers.IO) {
            val url = URL("https://api.anthropic.com/v1/messages")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                return@withContext "오류: ${conn.responseCode} $err"
            }
            val resp = JSONObject(conn.inputStream.bufferedReader().readText())
            // content 배열에서 type=text 블록 찾기 (thinking 블록이 먼저 올 수 있음)
            val content = resp.getJSONArray("content")
            var extracted = ""
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") {
                    extracted = block.getString("text")
                    break
                }
            }
            if (extracted.isEmpty()) extracted = "죄송해요, 응답을 받지 못했어요. 다시 시도해 주세요."
            extracted
        }

        // PLAN_CHANGE 파싱
        val planChangeRegex = Regex("PLAN_CHANGE:(\\w+):([\\d.]+):(.+)")
        val match = planChangeRegex.find(raw)
        val planChange = match?.let { m ->
            runCatching {
                PlanChange(
                    type = if (m.groupValues[1] == "REST") null else WorkoutType.valueOf(m.groupValues[1]),
                    km = m.groupValues[2].toDouble(),
                    reason = m.groupValues[3].trim(),
                )
            }.getOrNull()
        }
        val displayText = raw.replace(planChangeRegex, "").trimEnd()
        return ChatReply(text = displayText, planChange = planChange)
    }

}
