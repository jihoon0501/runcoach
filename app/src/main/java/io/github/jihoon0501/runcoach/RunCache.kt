package io.github.jihoon0501.runcoach

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 읽어서 분석한 러닝 기록을 폰에 저장해 두고, 다음부터는 새로 생긴 기록만 삼성 헬스에서 가져온다.
 *
 * - 앱을 열면 저장된 기록을 바로 보여준다 (삼성 헬스를 읽기 전에).
 * - 동기화는 '마지막 동기화 3일 전'부터 지금까지만 다시 읽어서 합친다 (늦게 올라온 기록, 수정된 기록 반영).
 * - 처음 한 번만 전체 기간(Config.LOAD_DAYS)을 읽는다. 읽는 기간을 늘리면 늘어난 앞쪽 기간만 추가로 읽는다.
 */
class RunStore(context: Context, private val repo: RunSource) {

    companion object {
        /** 기록 분석 방식을 바꾸면 이 숫자를 올려서, 저장된 기록을 한 번 새로 계산하게 한다 */
        const val CACHE_VERSION = 6
        private const val OVERLAP_DAYS = 3L
    }

    private val file = File(context.applicationContext.filesDir, "runs_cache.json")
    private val imported = ImportedStore(context.applicationContext)   // 파일(FIT/GPX/TCX)에서 가져온 기록

    /** 마지막으로 동기화에 성공한 시각 (ms), 없으면 0 */
    var lastSyncMs: Long = 0L
        private set

    /** 동기화된 걷기 요약 (러닝 기록과는 따로 보관) */
    var walks: List<WalkSession> = emptyList()
        private set

    private class Cache(
        val runs: List<RunSession>,
        val walks: List<WalkSession>,
        val coveredFromMs: Long,
        val syncedUntilMs: Long,
    )

    fun lastSyncText(): String {
        if (lastSyncMs <= 0L) return ""
        val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(lastSyncMs), ZoneId.systemDefault())
        return "${t.monthValue}/${t.dayOfMonth} %02d:%02d".format(t.hour, t.minute)
    }

    /** 저장된 기록 (없으면 빈 목록). 앱을 열자마자 화면에 보여주는 용도 */
    suspend fun loadCached(): List<RunSession> = withContext(Dispatchers.IO) {
        val c = readCache()
        walks = imported.mergeWalks(c?.walks ?: emptyList())
        imported.mergeRuns(c?.runs ?: emptyList())
    }

    /** 저장된 기록을 지운다 ('전체 다시 불러오기'용) */
    fun clear() {
        file.delete()
        lastSyncMs = 0L
        walks = emptyList()
    }

    /**
     * 삼성 헬스와 동기화하고 전체 기록을 돌려준다.
     * 저장된 기록이 없을 때만 전체 기간을 읽고, 그때는 최근 → 과거 순으로 읽은 만큼 [onPartial]로 알려준다.
     */
    suspend fun sync(onPartial: (List<RunSession>) -> Unit = {}): List<RunSession> {
        return try {
        val now = Instant.now()
        val desiredStart = now.minus(Duration.ofDays(Config.LOAD_DAYS))
        val cache = withContext(Dispatchers.IO) { readCache() }

        if (cache == null) {
            val (allRuns, allWalks) = repo.fetchAll(desiredStart, now) { r, w ->
                walks = imported.mergeWalks(w)
                onPartial(imported.mergeRuns(r))
            }
            walks = imported.mergeWalks(allWalks)
            withContext(Dispatchers.IO) { writeCache(allRuns, allWalks, desiredStart.toEpochMilli(), now.toEpochMilli()) }
            return imported.mergeRuns(allRuns)
        }

        val mergedRuns = LinkedHashMap<Long, RunSession>()
        val mergedWalks = LinkedHashMap<Long, WalkSession>()
        var coveredFrom = cache.coveredFromMs

        // 1) 새로 생긴 기록: 마지막 동기화 3일 전부터 지금까지
        val syncedUntil = Instant.ofEpochMilli(cache.syncedUntilMs)
        val incFrom = maxOf(syncedUntil.minus(Duration.ofDays(OVERLAP_DAYS)), desiredStart)
        val (freshRuns, freshWalks) = repo.fetchAll(incFrom, now)
        cache.runs.filter { it.start.isBefore(incFrom) }.forEach { mergedRuns[it.start.toEpochMilli()] = it }
        cache.walks.filter { it.start.isBefore(incFrom) }.forEach { mergedWalks[it.start.toEpochMilli()] = it }
        freshRuns.forEach { mergedRuns[it.start.toEpochMilli()] = it }   // 같은 기록은 새로 읽은 것으로 교체
        freshWalks.forEach { mergedWalks[it.start.toEpochMilli()] = it }

        // 2) 읽는 기간을 늘렸다면 늘어난 앞쪽 기간만 추가로 읽는다
        if (desiredStart.toEpochMilli() < coveredFrom) {
            val (olderRuns, olderWalks) = repo.fetchAll(desiredStart, Instant.ofEpochMilli(coveredFrom))
            olderRuns.forEach { if (!mergedRuns.containsKey(it.start.toEpochMilli())) mergedRuns[it.start.toEpochMilli()] = it }
            olderWalks.forEach { if (!mergedWalks.containsKey(it.start.toEpochMilli())) mergedWalks[it.start.toEpochMilli()] = it }
            coveredFrom = desiredStart.toEpochMilli()
        }

        val runList = mergedRuns.values.sortedBy { it.start }
        val walkList = mergedWalks.values.sortedBy { it.start }
        walks = imported.mergeWalks(walkList)
        withContext(Dispatchers.IO) { writeCache(runList, walkList, coveredFrom, now.toEpochMilli()) }
            imported.mergeRuns(runList)
        } catch (e: Throwable) {
            throw e
        }
    }

    // ───────────── 파일 읽기/쓰기 ─────────────

    private fun readCache(): Cache? {
        if (!file.exists()) return null
        return try {
            val o = JSONObject(file.readText())
            if (o.optInt("version") != CACHE_VERSION) {
                null
            } else {
                val arr = o.getJSONArray("runs")
                val runs = (0 until arr.length()).map { runFromJson(arr.getJSONObject(it)) }
                val wArr = o.optJSONArray("walks") ?: JSONArray()
                val walkList = (0 until wArr.length()).map { i ->
                    val j = wArr.getJSONObject(i)
                    WalkSession(
                        start = Instant.ofEpochMilli(j.getLong("start")),
                        durationSec = j.getLong("durationSec"),
                        distanceKm = j.getDouble("distanceKm"),
                        avgHr = j.intOrNull("avgHr"),
                    )
                }
                val synced = o.getLong("syncedUntil")
                lastSyncMs = synced
                walks = walkList
                Cache(runs, walkList, o.getLong("coveredFrom"), synced)
            }
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 특정 세션을 캐시에서 삭제한다.
     * 삼성 헬스 / Health Connect 원본은 건드리지 않음 — 앱 내 표시에서만 제거.
     */
    /**
     * 특정 세션을 캐시에서 삭제한다.
     * 삼성 헬스 / Health Connect 원본은 그대로 — 앱 캐시 파일만 수정.
     * 다음 sync()에서 원본이 복원될 수 있으므로 "숨기기" 성격임을 UI에 안내한다.
     */
    suspend fun deleteRun(startMs: Long): List<RunSession> = withContext(Dispatchers.IO) {
        val c = readCache() ?: return@withContext loadCached()
        val updated = c.runs.filter { it.start.toEpochMilli() != startMs }
        writeCache(updated, c.walks, c.coveredFromMs, c.syncedUntilMs)
        imported.mergeRuns(updated)   // imported 기록과 합쳐서 반환
    }

    /**
     * 특정 세션의 거리·시간·메모를 수정한다.
     * 삼성 헬스 원본은 그대로 — 앱 캐시 파일만 덮어씀.
     */
    suspend fun editRun(startMs: Long, newDistKm: Double, newDurSec: Long, newTitle: String?): List<RunSession> = withContext(Dispatchers.IO) {
        val c = readCache() ?: return@withContext loadCached()
        val updated = c.runs.map { r ->
            if (r.start.toEpochMilli() == startMs)
                r.copy(distanceKm = newDistKm, durationSec = newDurSec, title = newTitle)
            else r
        }
        writeCache(updated, c.walks, c.coveredFromMs, c.syncedUntilMs)
        imported.mergeRuns(updated)
    }

    private fun writeCache(runs: List<RunSession>, walkList: List<WalkSession>, coveredFromMs: Long, syncedUntilMs: Long) {
        val arr = JSONArray()
        runs.forEach { arr.put(runToJson(it)) }
        val wArr = JSONArray()
        walkList.forEach {
            wArr.put(JSONObject().apply {
                put("start", it.start.toEpochMilli())
                put("durationSec", it.durationSec)
                put("distanceKm", it.distanceKm)
                put("avgHr", it.avgHr ?: JSONObject.NULL)
            })
        }
        val o = JSONObject().apply {
            put("version", CACHE_VERSION)
            put("coveredFrom", coveredFromMs)
            put("syncedUntil", syncedUntilMs)
            put("runs", arr)
            put("walks", wArr)
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) {   // 일부 기기에서 덮어쓰기 이름 변경이 실패하면 직접 쓴다
            file.writeText(o.toString())
            tmp.delete()
        }
        lastSyncMs = syncedUntilMs
    }

    // ───────────── JSON 변환 ─────────────

    private fun JSONObject.intOrNull(k: String): Int? = if (has(k) && !isNull(k)) getInt(k) else null
    private fun JSONObject.longOrNull(k: String): Long? = if (has(k) && !isNull(k)) getLong(k) else null
    private fun JSONObject.doubleOrNull(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null
    private fun JSONObject.stringOrNull(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null

    private fun runToJson(r: RunSession): JSONObject = JSONObject().apply {
        put("start", r.start.toEpochMilli())
        put("durationSec", r.durationSec)
        put("distanceKm", r.distanceKm)
        put("avgHr", r.avgHr ?: JSONObject.NULL)
        put("maxHr", r.maxHr ?: JSONObject.NULL)
        put("cadence", r.cadence ?: JSONObject.NULL)
        put("lapCount", r.lapCount)
        put("title", r.title ?: JSONObject.NULL)
        put("steps", r.steps ?: JSONObject.NULL)
        put("splits", JSONArray(r.splits))
        put("mainKm", r.mainKm)
        put("mainSec", r.mainSec)
        put("vo2Max", r.vo2Max?.toDouble() ?: JSONObject.NULL)
        // GPS 경로 직렬화 (있을 때만)
        if (r.route.isNotEmpty()) {
            put("route", JSONArray().also { ra ->
                r.route.forEach { p ->
                    ra.put(JSONObject().apply {
                        put("lat", p.lat); put("lng", p.lng)
                        put("alt", p.altitudeM)
                        put("acc", p.accuracyM.toDouble())
                        put("ts", p.timestampMs)
                        p.hr?.let { put("hr", it) }
                        p.cadence?.let { put("cad", it) }
                        p.speedMps?.let { put("spd", it) }
                    })
                }
            })
        }
        put("segments", JSONArray().also { a ->
            r.segments.forEach { g ->
                a.put(JSONObject().apply {
                    put("role", g.role.name)
                    put("kind", g.kind.name)
                    put("startSec", g.startSec)
                    put("durSec", g.durSec)
                    put("distanceKm", g.distanceKm)
                    put("avgHr", g.avgHr ?: JSONObject.NULL)
                    put("cadence", g.cadence ?: JSONObject.NULL)
                })
            }
        })
    }

    private fun runFromJson(o: JSONObject): RunSession {
        val sp = o.getJSONArray("splits")
        val sg = o.getJSONArray("segments")
        val route = o.optJSONArray("route")?.let { ra ->
            (0 until ra.length()).mapNotNull { i ->
                val p = ra.optJSONObject(i) ?: return@mapNotNull null
                runCatching {
                    RoutePoint(
                        lat         = p.getDouble("lat"),
                        lng         = p.getDouble("lng"),
                        altitudeM   = p.optDouble("alt", 0.0),
                        accuracyM   = p.optDouble("acc", 0.0).toFloat(),
                        timestampMs = p.optLong("ts", 0L),
                        hr          = if (p.has("hr")) p.getInt("hr") else null,
                        cadence     = if (p.has("cad")) p.getInt("cad") else null,
                        speedMps    = if (p.has("spd")) p.optDouble("spd") else null,
                    )
                }.getOrNull()
            }
        } ?: emptyList()
        return RunSession(
            start = Instant.ofEpochMilli(o.getLong("start")),
            durationSec = o.getLong("durationSec"),
            distanceKm = o.getDouble("distanceKm"),
            avgHr = o.intOrNull("avgHr"),
            maxHr = o.intOrNull("maxHr"),
            cadence = o.intOrNull("cadence"),
            lapCount = o.optInt("lapCount", 0),
            title = o.stringOrNull("title"),
            steps = o.longOrNull("steps"),
            splits = (0 until sp.length()).map { sp.getInt(it) },
            mainKm = o.getDouble("mainKm"),
            mainSec = o.getLong("mainSec"),
            vo2Max = o.doubleOrNull("vo2Max")?.toFloat(),
            segments = (0 until sg.length()).map { i ->
                val g = sg.getJSONObject(i)
                RunSegment(
                    role = SegRole.valueOf(g.getString("role")),
                    kind = SegKind.valueOf(g.getString("kind")),
                    startSec = g.getLong("startSec"),
                    durSec = g.getLong("durSec"),
                    distanceKm = g.getDouble("distanceKm"),
                    avgHr = g.intOrNull("avgHr"),
                    cadence = g.intOrNull("cadence"),
                )
            },
            route = route,
        )
    }
}
