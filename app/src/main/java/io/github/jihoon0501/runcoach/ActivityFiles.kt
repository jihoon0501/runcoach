package io.github.jihoon0501.runcoach

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.xml.parsers.SAXParserFactory
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * 운동 파일(FIT, GPX, TCX)을 읽는 코드. 안드로이드에 의존하지 않는 순수 코틀린이다.
 * - GPS 좌표는 거리 계산에만 쓰고 결과에 남기지 않는다 (위치 정보는 저장하지 않는다).
 * - 파일이 깨져 있어도 앱이 멈추지 않게, 읽는 도중 문제가 생기면 오류 문구를 돌려준다.
 */

enum class FileSport { RUN, WALK, OTHER }

/** 한 시점의 기록. distM은 시작부터의 누적 거리(m), cadence는 파일에 적힌 원래 값(보통 한 발 기준) */
class FilePoint(val tMs: Long, val distM: Double?, val speed: Double?, val hr: Int?, val cadence: Int?)

class ParsedActivity(
    val sport: FileSport,
    val startMs: Long,
    val endMs: Long,
    val distanceM: Double?,
    val avgHr: Int?,
    val maxHr: Int?,
    val avgCadence: Int?,
    val points: List<FilePoint>,
    val title: String?,
)

object ActivityFileReader {

    class Result(val activities: List<ParsedActivity>, val error: String?)

    private const val MAX_BYTES = 40 * 1024 * 1024

    fun read(fileName: String, bytes: ByteArray): Result {
        if (bytes.size > MAX_BYTES) return Result(emptyList(), "파일이 너무 커요 (40MB 이하만 읽어요)")
        if (bytes.isEmpty()) return Result(emptyList(), "빈 파일이에요")
        return try {
            when {
                FitParser.looksLikeFit(bytes) -> FitParser.parse(bytes)
                looksLikeXml(bytes) -> {
                    val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8)
                    when {
                        head.contains("<gpx", ignoreCase = true) -> XmlActivity.parseGpx(bytes)
                        head.contains("TrainingCenterDatabase", ignoreCase = true) -> XmlActivity.parseTcx(bytes)
                        else -> Result(emptyList(), "지원하지 않는 XML 형식이에요 (GPX, TCX만 읽어요)")
                    }
                }
                else -> Result(emptyList(), "지원하지 않는 파일이에요 (FIT, GPX, TCX만 읽어요)")
            }
        } catch (e: Throwable) {
            Result(emptyList(), "파일을 읽지 못했어요 (${e::class.java.simpleName})")
        }
    }

    private fun looksLikeXml(b: ByteArray): Boolean {
        var i = 0
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) i = 3
        while (i < b.size && (b[i] == ' '.code.toByte() || b[i] == '\n'.code.toByte() || b[i] == '\r'.code.toByte() || b[i] == '\t'.code.toByte())) i++
        return i < b.size && b[i] == '<'.code.toByte()
    }

    /** 두 지점 사이의 거리(m) */
    internal fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    /** 종목 정보가 없을 때 평균 속도(m/s)로 러닝/걷기를 추정한다 */
    internal fun sportBySpeed(avgSpeed: Double): FileSport = when {
        avgSpeed in 1.8..5.0 -> FileSport.RUN
        avgSpeed in 0.8..1.8 -> FileSport.WALK
        else -> FileSport.OTHER
    }
}

// ───────────────────────── FIT ─────────────────────────

internal object FitParser {

    private const val FIT_EPOCH_OFFSET_SEC = 631065600L   // 1989-12-31 → 1970-01-01

    fun looksLikeFit(b: ByteArray): Boolean =
        b.size >= 14 && b[8] == '.'.code.toByte() && b[9] == 'F'.code.toByte() && b[10] == 'I'.code.toByte() && b[11] == 'T'.code.toByte()

    private class FieldDef(val num: Int, val size: Int, val baseType: Int)
    private class MsgDef(val global: Int, val big: Boolean, val fields: List<FieldDef>, val devSize: Int)

    private class Rec(
        val ts: Long, var lat: Int?, var lon: Int?, var dist: Double?, var speed: Double?, var hr: Int?, var cad: Int?,
    )

    private class Sess(
        val startSec: Long, val endSec: Long, val sport: Int, val sub: Int,
        val distM: Double?, val avgHr: Int?, val maxHr: Int?, val avgCad: Int?,
    )

    fun parse(b: ByteArray): ActivityFileReader.Result {
        val recs = ArrayList<Rec>()
        val sessions = ArrayList<Sess>()
        var pos = 0
        var any = false
        while (pos + 14 <= b.size && looksLikeFit(b.copyOfRange(pos, minOf(b.size, pos + 14)))) {
            val headerSize = b[pos].toInt() and 0xFF
            val dataSize = u(b, pos + 4, 4, false)
            val end = (pos + headerSize + dataSize).toInt().coerceAtMost(b.size)
            parseData(b, pos + headerSize, end, recs, sessions)
            any = true
            pos = end + 2   // 뒤의 CRC 2바이트는 건너뛴다 (이어 붙은 FIT 파일이 있으면 계속 읽는다)
        }
        if (!any) return ActivityFileReader.Result(emptyList(), "FIT 파일 형식이 아니에요")
        if (sessions.isEmpty()) return ActivityFileReader.Result(emptyList(), "운동 요약(세션) 정보가 없는 FIT 파일이에요")

        val out = ArrayList<ParsedActivity>()
        for (s in sessions) {
            val inRange = recs.filter { it.ts in s.startSec..s.endSec }.sortedBy { it.ts }
            val pts = buildPoints(inRange)
            out += ParsedActivity(
                sport = when (s.sport) {
                    1 -> FileSport.RUN
                    11 -> FileSport.WALK
                    else -> FileSport.OTHER
                },
                startMs = (s.startSec + FIT_EPOCH_OFFSET_SEC) * 1000,
                endMs = (s.endSec + FIT_EPOCH_OFFSET_SEC) * 1000,
                distanceM = s.distM ?: pts.lastOrNull { it.distM != null }?.distM,
                avgHr = s.avgHr,
                maxHr = s.maxHr,
                avgCadence = s.avgCad,
                points = pts,
                title = null,
            )
        }
        return ActivityFileReader.Result(out, null)
    }

    /** 기록(record)들에서 누적 거리와 속도가 비어 있으면 좌표로 계산해서 채운다. 좌표는 여기서 버린다 */
    private fun buildPoints(rs: List<Rec>): List<FilePoint> {
        val out = ArrayList<FilePoint>(rs.size)
        var acc = 0.0
        var pLat: Int? = null
        var pLon: Int? = null
        val k = 180.0 / 2147483648.0
        for (r in rs) {
            var d = r.dist
            if (d == null && r.lat != null && r.lon != null) {
                if (pLat != null && pLon != null) {
                    acc += ActivityFileReader.haversine(pLat * k, pLon * k, r.lat!! * k, r.lon!! * k)
                }
                d = acc
            }
            if (r.lat != null && r.lon != null) {
                pLat = r.lat
                pLon = r.lon
            }
            out += FilePoint(
                tMs = (r.ts + FIT_EPOCH_OFFSET_SEC) * 1000, distM = d, speed = r.speed, hr = r.hr, cadence = r.cad,
            )
        }
        return out
    }

    private fun parseData(b: ByteArray, start: Int, end: Int, recs: MutableList<Rec>, sessions: MutableList<Sess>) {
        val defs = arrayOfNulls<MsgDef>(16)
        var pos = start
        var lastTs = 0L
        while (pos < end) {
            val header = b[pos].toInt() and 0xFF
            pos++
            if (header and 0x80 != 0) {
                // 압축 타임스탬프 헤더: 데이터 메시지 + 5비트 시간 오프셈
                val local = (header shr 5) and 0x03
                val offset = header and 0x1F
                val def = defs[local] ?: return
                var ts = (lastTs and 0x1FL.inv()) + offset
                if (offset < (lastTs and 0x1F)) ts += 0x20
                lastTs = ts
                val vals = readFields(b, pos, def)
                pos += def.fields.sumOf { it.size } + def.devSize
                handle(def.global, vals, ts, recs, sessions)
            } else if (header and 0x40 != 0) {
                val local = header and 0x0F
                val hasDev = header and 0x20 != 0
                pos++                                   // 예약 바이트
                val big = (b[pos].toInt() and 0xFF) == 1
                pos++
                val global = u(b, pos, 2, big).toInt()
                pos += 2
                val n = b[pos].toInt() and 0xFF
                pos++
                val fields = ArrayList<FieldDef>(n)
                for (i in 0 until n) {
                    fields += FieldDef(b[pos].toInt() and 0xFF, b[pos + 1].toInt() and 0xFF, b[pos + 2].toInt() and 0xFF)
                    pos += 3
                }
                var devSize = 0
                if (hasDev) {
                    val dn = b[pos].toInt() and 0xFF
                    pos++
                    for (i in 0 until dn) {
                        devSize += b[pos + 1].toInt() and 0xFF
                        pos += 3
                    }
                }
                defs[local] = MsgDef(global, big, fields, devSize)
            } else {
                val local = header and 0x0F
                val def = defs[local] ?: return
                val vals = readFields(b, pos, def)
                pos += def.fields.sumOf { it.size } + def.devSize
                val ts = vals[253]
                if (ts != null) lastTs = ts
                handle(def.global, vals, ts ?: lastTs, recs, sessions)
            }
        }
    }

    /** 필요한 숫자 필드만 값(원래 정수)으로 읽는다. 유효하지 않은 값(0xFF 등)은 넣지 않는다 */
    private fun readFields(b: ByteArray, startPos: Int, def: MsgDef): Map<Int, Long> {
        val m = HashMap<Int, Long>(16)
        var p = startPos
        for (f in def.fields) {
            val type = f.baseType and 0x1F
            val elem = when (type) {
                0, 1, 2, 7, 10, 13 -> 1
                3, 4, 11 -> 2
                5, 6, 8, 12 -> 4
                9, 14, 15, 16 -> 8
                else -> 1
            }
            if (f.size == elem && type != 7 && type != 8 && type != 9 && elem <= 4 && p + f.size <= b.size) {
                val raw = u(b, p, f.size, def.big)
                val valid = when (type) {
                    0, 2, 13 -> raw != 0xFFL
                    1 -> raw != 0x7FL
                    3 -> raw != 0x7FFFL
                    4 -> raw != 0xFFFFL
                    5 -> raw != 0x7FFFFFFFL
                    6 -> raw != 0xFFFFFFFFL
                    10 -> raw != 0L
                    11 -> raw != 0L
                    12 -> raw != 0L
                    else -> true
                }
                if (valid) {
                    // 부호 있는 정수는 부호를 맞춰 준다
                    m[f.num] = when (type) {
                        1 -> raw.toByte().toLong()
                        3 -> raw.toShort().toLong()
                        5 -> raw.toInt().toLong()
                        else -> raw
                    }
                }
            }
            p += f.size
        }
        return m
    }

    private fun handle(global: Int, v: Map<Int, Long>, ts: Long, recs: MutableList<Rec>, sessions: MutableList<Sess>) {
        when (global) {
            20 -> {
                val speedRaw = v[73]?.let { it / 1000.0 } ?: v[6]?.let { it / 1000.0 }
                recs += Rec(
                    ts = ts,
                    lat = v[0]?.toInt(),
                    lon = v[1]?.toInt(),
                    dist = v[5]?.let { it / 100.0 },
                    speed = speedRaw,
                    hr = v[3]?.toInt(),
                    cad = v[4]?.toInt(),
                )
            }
            18 -> {
                val st = v[2] ?: return
                val elapsed = (v[7] ?: v[8] ?: 0L) / 1000.0
                val en = v[253]?.takeIf { it >= st } ?: (st + elapsed.toLong())
                sessions += Sess(
                    startSec = st, endSec = max(en, st),
                    sport = v[5]?.toInt() ?: 0, sub = v[6]?.toInt() ?: 0,
                    distM = v[9]?.let { it / 100.0 },
                    avgHr = v[16]?.toInt(), maxHr = v[17]?.toInt(), avgCad = v[18]?.toInt(),
                )
            }
        }
    }

    private fun u(b: ByteArray, off: Int, size: Int, big: Boolean): Long {
        var r = 0L
        if (big) {
            for (i in 0 until size) r = (r shl 8) or (b[off + i].toLong() and 0xFF)
        } else {
            for (i in size - 1 downTo 0) r = (r shl 8) or (b[off + i].toLong() and 0xFF)
        }
        return r
    }
}

// ───────────────────────── GPX / TCX ─────────────────────────

internal object XmlActivity {

    private fun newParser() = SAXParserFactory.newInstance().apply {
        isNamespaceAware = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }   // 외부 개체 공격 방지
    }.newSAXParser()

    private fun parseTime(s: String): Long? {
        val t = s.trim()
        if (t.isEmpty()) return null
        return try {
            OffsetDateTime.parse(t).toInstant().toEpochMilli()
        } catch (e: Exception) {
            try {
                LocalDateTime.parse(t).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun local(q: String): String = q.substringAfterLast(':')

    /** 파싱 중 모은 점들을 한 활동으로 정리한다 */
    private class Collector {
        val pts = ArrayList<FilePoint>()
        var title: String? = null
        var typeText: String? = null
        var tcxSport: String? = null
        private var accDist = 0.0
        private var pLat: Double? = null
        private var pLon: Double? = null

        fun add(tMs: Long, lat: Double?, lon: Double?, dist: Double?, hr: Int?, cad: Int?, speed: Double?) {
            var d = dist
            if (lat != null && lon != null) {
                val a = pLat
                val o = pLon
                if (d == null) {
                    if (a != null && o != null) {
                        val step = ActivityFileReader.haversine(a, o, lat, lon)
                        if (step < 2000.0) accDist += step    // 비정상적으로 튄 좌표는 무시
                    }
                    d = accDist
                }
                pLat = lat
                pLon = lon
            }
            pts += FilePoint(tMs, d, speed, hr, cad)
        }

        fun finish(): ParsedActivity? {
            val sorted = pts.sortedBy { it.tMs }
            if (sorted.size < 2) return null
            val start = sorted.first().tMs
            val end = sorted.last().tMs
            val dist = sorted.lastOrNull { it.distM != null }?.distM
            val hrs = sorted.mapNotNull { it.hr }
            val cads = sorted.mapNotNull { it.cadence }.filter { it > 0 }
            val avgSpeed = if (dist != null && end > start) dist / ((end - start) / 1000.0) else 0.0
            val tt = (typeText ?: tcxSport)?.lowercase() ?: ""
            val sport = when {
                tt.contains("run") || tt == "9" -> FileSport.RUN
                tt.contains("walk") -> FileSport.WALK
                tt.contains("bik") || tt.contains("cycl") || tt.contains("swim") || tt.contains("hik") -> FileSport.OTHER
                else -> ActivityFileReader.sportBySpeed(avgSpeed)
            }
            return ParsedActivity(
                sport = sport, startMs = start, endMs = end, distanceM = dist,
                avgHr = if (hrs.isNotEmpty()) hrs.average().toInt() else null,
                maxHr = hrs.maxOrNull(),
                avgCadence = if (cads.isNotEmpty()) cads.average().toInt() else null,
                points = sorted, title = title,
            )
        }
    }

    fun parseGpx(bytes: ByteArray): ActivityFileReader.Result {
        val acts = ArrayList<ParsedActivity>()
        var cur: Collector? = null
        val text = StringBuilder()
        var lat: Double? = null
        var lon: Double? = null
        var time: Long? = null
        var hr: Int? = null
        var cad: Int? = null
        var inPt = false
        var inTrk = false
        newParser().parse(InputSource(ByteArrayInputStream(bytes)), object : DefaultHandler() {
            override fun startElement(u: String?, ln: String?, q: String, a: Attributes) {
                text.setLength(0)
                when (local(q)) {
                    "trk" -> { cur = Collector(); inTrk = true }
                    "trkpt" -> {
                        inPt = true
                        lat = a.getValue("lat")?.toDoubleOrNull()
                        lon = a.getValue("lon")?.toDoubleOrNull()
                        time = null; hr = null; cad = null
                    }
                }
            }
            override fun characters(ch: CharArray, s: Int, l: Int) { text.append(ch, s, l) }
            override fun endElement(u: String?, ln: String?, q: String) {
                val n = local(q)
                val t = text.toString().trim()
                when (n) {
                    "time" -> if (inPt) time = parseTime(t)
                    "hr", "heartrate" -> if (inPt) hr = t.toDoubleOrNull()?.toInt()
                    "cad", "cadence" -> if (inPt) cad = t.toDoubleOrNull()?.toInt()
                    "name" -> if (inTrk && !inPt && cur?.title == null) cur?.title = t.ifEmpty { null }
                    "type" -> if (inTrk && !inPt) cur?.typeText = t
                    "trkpt" -> {
                        val tm = time
                        if (tm != null) cur?.add(tm, lat, lon, null, hr, cad, null)
                        inPt = false
                    }
                    "trk" -> {
                        cur?.finish()?.let { acts += it }
                        cur = null
                        inTrk = false
                    }
                }
                text.setLength(0)
            }
        })
        return if (acts.isEmpty()) ActivityFileReader.Result(emptyList(), "GPX에서 시간이 있는 경로 기록을 찾지 못했어요")
        else ActivityFileReader.Result(acts, null)
    }

    fun parseTcx(bytes: ByteArray): ActivityFileReader.Result {
        val acts = ArrayList<ParsedActivity>()
        var cur: Collector? = null
        val text = StringBuilder()
        var inTp = false
        var inHr = false
        var lat: Double? = null
        var lon: Double? = null
        var time: Long? = null
        var dist: Double? = null
        var hr: Int? = null
        var cad: Int? = null
        var runCad: Int? = null
        var speed: Double? = null
        newParser().parse(InputSource(ByteArrayInputStream(bytes)), object : DefaultHandler() {
            override fun startElement(u: String?, ln: String?, q: String, a: Attributes) {
                text.setLength(0)
                when (local(q)) {
                    "Activity" -> { cur = Collector(); cur?.tcxSport = a.getValue("Sport") }
                    "Trackpoint" -> {
                        inTp = true
                        lat = null; lon = null; time = null; dist = null; hr = null; cad = null; runCad = null; speed = null
                    }
                    "HeartRateBpm" -> inHr = true
                }
            }
            override fun characters(ch: CharArray, s: Int, l: Int) { text.append(ch, s, l) }
            override fun endElement(u: String?, ln: String?, q: String) {
                val n = local(q)
                val t = text.toString().trim()
                if (inTp) {
                    when (n) {
                        "Time" -> time = parseTime(t)
                        "LatitudeDegrees" -> lat = t.toDoubleOrNull()
                        "LongitudeDegrees" -> lon = t.toDoubleOrNull()
                        "DistanceMeters" -> dist = t.toDoubleOrNull()
                        "Value" -> if (inHr) hr = t.toDoubleOrNull()?.toInt()
                        "Cadence" -> cad = t.toDoubleOrNull()?.toInt()
                        "RunCadence" -> runCad = t.toDoubleOrNull()?.toInt()
                        "Speed" -> speed = t.toDoubleOrNull()
                    }
                }
                when (n) {
                    "HeartRateBpm" -> inHr = false
                    "Trackpoint" -> {
                        val tm = time
                        if (tm != null) cur?.add(tm, lat, lon, dist, hr, runCad ?: cad, speed)
                        inTp = false
                    }
                    "Activity" -> {
                        cur?.finish()?.let { acts += it }
                        cur = null
                    }
                }
                text.setLength(0)
            }
        })
        return if (acts.isEmpty()) ActivityFileReader.Result(emptyList(), "TCX에서 시간이 있는 기록을 찾지 못했어요")
        else ActivityFileReader.Result(acts, null)
    }
}
