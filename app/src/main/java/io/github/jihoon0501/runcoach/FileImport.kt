package io.github.jihoon0501.runcoach

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant

/**
 * 파일(FIT, GPX, TCX)에서 가져온 러닝·걷기 기록을 따로 보관한다 (imported_runs.json).
 * 삼성 헬스/Health Connect 동기화나 '전체 다시 불러오기'와 상관없이 남고, 같은 운동이 이미 동기화돼 있으면 동기화된 쪽을 쓴다.
 */
class ImportedStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "imported_runs.json")

    class Added(val runs: Int, val walks: Int, val duplicates: Int)

    private fun near(a: Instant, b: Instant) = Math.abs(a.toEpochMilli() - b.toEpochMilli()) < 120_000L

    fun load(): Pair<List<RunSession>, List<WalkSession>> {
        if (!file.exists()) return emptyList<RunSession>() to emptyList()
        return try {
            val o = JSONObject(file.readText())
            val ra = o.optJSONArray("runs") ?: JSONArray()
            val wa = o.optJSONArray("walks") ?: JSONArray()
            (0 until ra.length()).map { runFrom(ra.getJSONObject(it)) } to (0 until wa.length()).map { walkFrom(wa.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList<RunSession>() to emptyList()
        }
    }

    fun count(): Int = load().let { it.first.size + it.second.size }

    fun clear() {
        file.delete()
    }

    /** 이미 가져온 기록(시작 시각이 2분 안)과 겹치는 것은 건너뛰고 저장한다 */
    fun add(newRuns: List<RunSession>, newWalks: List<WalkSession>): Added {
        val (oldRuns, oldWalks) = load()
        val runs = oldRuns.toMutableList()
        val walks = oldWalks.toMutableList()
        var addedRuns = 0
        var addedWalks = 0
        var dup = 0
        for (r in newRuns) {
            if (runs.any { near(it.start, r.start) }) dup++ else { runs += r; addedRuns++ }
        }
        for (w in newWalks) {
            if (walks.any { near(it.start, w.start) }) dup++ else { walks += w; addedWalks++ }
        }
        if (addedRuns + addedWalks > 0) save(runs.sortedBy { it.start }, walks.sortedBy { it.start })
        return Added(addedRuns, addedWalks, dup)
    }

    /** 동기화된 기록에 가져온 기록을 합친다 (시작 시각이 2분 안에 겹치면 동기화된 기록을 쓴다) */
    fun mergeRuns(repoRuns: List<RunSession>): List<RunSession> {
        val imp = load().first
        if (imp.isEmpty()) return repoRuns
        val extra = imp.filter { i -> repoRuns.none { near(it.start, i.start) } }
        return (repoRuns + extra).sortedBy { it.start }
    }

    fun mergeWalks(repoWalks: List<WalkSession>): List<WalkSession> {
        val imp = load().second
        if (imp.isEmpty()) return repoWalks
        val extra = imp.filter { i -> repoWalks.none { near(it.start, i.start) } }
        return (repoWalks + extra).sortedBy { it.start }
    }

    private fun save(runs: List<RunSession>, walks: List<WalkSession>) {
        val ra = JSONArray()
        runs.forEach { ra.put(runTo(it)) }
        val wa = JSONArray()
        walks.forEach {
            wa.put(JSONObject().apply {
                put("start", it.start.toEpochMilli())
                put("durationSec", it.durationSec)
                put("distanceKm", it.distanceKm)
                put("avgHr", it.avgHr ?: JSONObject.NULL)
            })
        }
        file.writeText(JSONObject().apply { put("runs", ra); put("walks", wa) }.toString())
    }

    private fun JSONObject.intOrNull(k: String): Int? = if (has(k) && !isNull(k)) getInt(k) else null
    private fun JSONObject.stringOrNull(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null

    private fun walkFrom(j: JSONObject) = WalkSession(
        start = Instant.ofEpochMilli(j.getLong("start")),
        durationSec = j.getLong("durationSec"),
        distanceKm = j.getDouble("distanceKm"),
        avgHr = j.intOrNull("avgHr"),
    )

    private fun runTo(r: RunSession): JSONObject = JSONObject().apply {
        put("start", r.start.toEpochMilli())
        put("durationSec", r.durationSec)
        put("distanceKm", r.distanceKm)
        put("avgHr", r.avgHr ?: JSONObject.NULL)
        put("maxHr", r.maxHr ?: JSONObject.NULL)
        put("cadence", r.cadence ?: JSONObject.NULL)
        put("title", r.title ?: JSONObject.NULL)
        put("splits", JSONArray(r.splits))
        put("mainKm", r.mainKm)
        put("mainSec", r.mainSec)
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

    private fun runFrom(o: JSONObject): RunSession {
        val sp = o.getJSONArray("splits")
        val sg = o.getJSONArray("segments")
        return RunSession(
            start = Instant.ofEpochMilli(o.getLong("start")),
            durationSec = o.getLong("durationSec"),
            distanceKm = o.getDouble("distanceKm"),
            avgHr = o.intOrNull("avgHr"),
            maxHr = o.intOrNull("maxHr"),
            cadence = o.intOrNull("cadence"),
            lapCount = 0,
            title = o.stringOrNull("title"),
            splits = (0 until sp.length()).map { sp.getInt(it) },
            mainKm = o.getDouble("mainKm"),
            mainSec = o.getLong("mainSec"),
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
        )
    }
}

class ImportSummary(val runs: Int, val walks: Int, val duplicates: Int, val skipped: Int, val errors: List<String>) {
    fun message(): String = buildString {
        if (runs + walks > 0) append("러닝 ${runs}개, 걷기 ${walks}개를 가져왔어요 ✅")
        else append("가져온 기록이 없어요")
        if (duplicates > 0) append("\n이미 가져온 기록 ${duplicates}개는 건너뛰었어요")
        if (skipped > 0) append("\n러닝·걷기가 아니거나 너무 짧은 활동 ${skipped}개는 건너뛰었어요")
        errors.take(3).forEach { append("\n⚠ ").append(it) }
        if (errors.size > 3) append("\n…외 ${errors.size - 3}개 파일은 읽지 못했어요")
        if (runs > 0) append("\n계획 탭의 '새 계획'을 누르면 새 기록으로 계획을 다시 만들어요")
    }
}

object FileImport {

    private const val MAX_FILES = 200
    private const val MAX_BYTES = 40 * 1024 * 1024

    private fun displayName(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: (uri.lastPathSegment ?: "파일")
        } catch (e: Exception) {
            uri.lastPathSegment ?: "파일"
        }
    }

    private fun readBytes(context: Context, uri: Uri): ByteArray? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        return input.use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            var tooBig = false
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) {
                    tooBig = true
                    break
                }
                out.write(buf, 0, n)
            }
            if (tooBig) null else out.toByteArray()
        }
    }

    /** 고른 파일들을 읽어서 러닝/걷기 기록으로 바꿔 저장한다 */
    suspend fun importUris(context: Context, store: ImportedStore, uris: List<Uri>): ImportSummary = withContext(Dispatchers.IO) {
        val newRuns = mutableListOf<RunSession>()
        val newWalks = mutableListOf<WalkSession>()
        val errors = mutableListOf<String>()
        var skipped = 0
        for (uri in uris.take(MAX_FILES)) {
            val name = displayName(context, uri)
            val bytes = try { readBytes(context, uri) } catch (e: Exception) { null }
            if (bytes == null) {
                errors += "$name: 읽지 못했거나 너무 커요"
                continue
            }
            val r = ActivityFileReader.read(name, bytes)
            if (r.error != null) {
                errors += "$name: ${r.error}"
                continue
            }
            for (a in r.activities) {
                val run = ActivityConverter.toRun(a)
                val walk = if (run == null) ActivityConverter.toWalk(a) else null
                when {
                    run != null -> newRuns += run
                    walk != null -> newWalks += walk
                    else -> skipped++
                }
            }
        }
        val added = store.add(newRuns, newWalks)
        ImportSummary(added.runs, added.walks, added.duplicates, skipped, errors)
    }
}

/** 설정 탭: 삼성 헬스·Health Connect를 못 쓸 때 FIT/GPX/TCX 파일로 기록을 가져온다 */
@Composable
fun ImportCard(onImported: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ImportedStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var count by remember { mutableStateOf(store.count()) }
    var confirmClear by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                busy = true
                val s = try {
                    FileImport.importUris(context.applicationContext, store, uris)
                } catch (e: Throwable) {
                    ImportSummary(0, 0, 0, 0, listOf("가져오지 못했어요 (${e::class.java.simpleName})"))
                }
                msg = s.message()
                count = store.count()
                busy = false
                if (s.runs + s.walks > 0) onImported()
            }
        }
    }

    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("파일에서 가져오기 (선택)")
        Spacer(Modifier.height(4.dp))
        Text(
            "가민, 코로스, 폴라 등에서 내보낸 FIT, GPX, TCX 파일로 러닝·걷기 기록을 가져와요. 삼성 헬스·Health Connect를 못 쓸 때도 쓸 수 있어요. " +
                "파일은 이 폰 안에서만 읽고, 파일 속 GPS 위치는 거리·속도 계산에만 쓰고 저장하지 않아요.",
            color = RunColors.TextDim, fontSize = 11.sp, lineHeight = 16.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "가민 커넥트 웹사이트의 활동 화면에서 ⚙ 메뉴의 '원본 내보내기'로 FIT 파일을 받을 수 있어요 (메뉴 이름은 바뀔 수 있어요).",
            color = RunColors.TextDim, fontSize = 10.sp, lineHeight = 15.sp,
        )
        if (count > 0) {
            Spacer(Modifier.height(4.dp))
            Text("지금까지 가져온 기록 ${count}개", color = Color.White, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = RunColors.Lime, contentColor = RunColors.Bg),
                modifier = Modifier.height(36.dp),
            ) { Text(if (busy) "읽는 중..." else "파일 선택", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            if (count > 0) {
                OutlinedButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.height(36.dp),
                ) { Text("가져온 기록 지우기", color = RunColors.Pink, fontSize = 12.sp) }
            }
        }
        if (msg.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(msg, color = Color.White, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("가져온 기록을 지울까요?") },
            text = { Text("파일에서 가져온 ${count}개 기록이 이 앱에서 삭제돼요. 원본 파일과 삼성 헬스·Health Connect의 기록은 그대로예요.") },
            confirmButton = {
                TextButton(onClick = {
                    store.clear()
                    count = 0
                    msg = "지웠어요"
                    confirmClear = false
                    onImported()
                }) { Text("지우기") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("취소") } },
        )
    }
}
