package io.github.jihoon0501.runcoach

/**
 * 속도(m/s)·심박(bpm)·케이던스(spm)로 운동을 정지/걷기/조깅/달리기 구간으로 나눈다.
 *
 * 분류 기준 (우선순위):
 *  1. 속도 < 0.5 m/s → REST (일시정지)
 *  2. 케이던스 ≥ 155 spm 이면 JOG/RUN으로 상향 보정 — 느려도 뛰고 있음
 *  3. 심박 ≥ runHrMin(기본 130 bpm) 이면 JOG 이상으로 유지
 *  4. 속도 기반 1차 분류 → 케이던스·심박으로 상향/하향 보정
 *  5. 짧은 구간(90초 미만) 흡수 → 같은 종류 병합 반복
 *
 * 인터벌 보정: 빠른 반복과 느린 준비·회복이 뚜렷이 갈리면 경계 속도를 '달리기' 기준으로 사용.
 */
object RunAnalyzer {

    class Result(
        val splits: List<Int>,
        val mainKm: Double,
        val mainSec: Long,
        val avgHr: Int?,
        val cadence: Int?,
        val segments: List<RunSegment>,
    )

    private val empty = Result(emptyList(), 0.0, 0, null, null, emptyList())

    private class Raw(var kind: SegKind, val startIdx: Int, var endIdx: Int)

    private const val RUN_MIN  = 2.3    // 기본 '달리기' 기준 속도 (7'15"/km)
    private const val WALK_MAX = 1.7    // 걷기 최대 속도 (9'48"/km)
    private const val CAD_RUN  = 155.0  // 이 이상이면 뛰는 중 (케이던스 기준)
    private const val CAD_JOG  = 140.0  // 이 이상이면 조깅 중
    private const val HR_RUN   = 140    // 이 이상이면 달리기 심박
    private const val HR_JOG   = 125    // 이 이상이면 조깅 심박

    /**
     * 속도·케이던스·심박을 종합해 구간 종류를 판정한다.
     * 케이던스가 있으면 케이던스를 우선하고, 심박으로 보조 보정한다.
     */
    private fun kindOf(
        v: Double,
        runMin: Double,
        cadence: Double? = null,  // spm (스텝/분)
        hr: Double? = null,       // bpm
    ): SegKind {
        if (v < 0.5) return SegKind.REST

        // 케이던스 기반 1차 판정 (신뢰도 높음)
        if (cadence != null && cadence > 0) {
            return when {
                cadence >= CAD_RUN  -> if (v >= runMin) SegKind.RUN else SegKind.JOG
                cadence >= CAD_JOG  -> SegKind.JOG
                cadence < 120.0     -> SegKind.WALK  // 느린 걷기 케이던스
                else -> speedKind(v, runMin)          // 중간 케이던스는 속도로 판정
            }
        }

        // 케이던스 없을 때 심박 보조
        val speedKind = speedKind(v, runMin)
        if (hr != null && hr > 0) {
            return when {
                // 속도는 느리지만 심박이 높으면 한 단계 상향 (오르막 등)
                speedKind == SegKind.WALK && hr >= HR_JOG -> SegKind.JOG
                speedKind == SegKind.JOG  && hr >= HR_RUN -> SegKind.RUN
                // 속도는 빠르지만 심박이 낮으면 하향하지 않음 (가속 직후 등)
                else -> speedKind
            }
        }

        return speedKind
    }

    private fun speedKind(v: Double, runMin: Double): SegKind = when {
        v < WALK_MAX -> SegKind.WALK
        v < runMin   -> SegKind.JOG
        else         -> SegKind.RUN
    }

    /**
     * 평활화한 속도 [sm]이 '빠른 반복'과 '느린 준비·회복' 두 무리로 뚜렷이 갈리면 경계 속도(m/s)를 돌려준다. 아니면 null.
     * 오쓰(Otsu) 방법으로 경계를 찾고, 아래를 모두 만족할 때만 쓴다.
     *  - 달리는 속도(1 m/s 이상)가 5분 이상이고, 경계가 2.6 m/s(6'25"/km) 이상
     *  - 빠른 무리 평균이 느린 무리 평균의 1.25배 이상이고 0.7 m/s 이상 차이, 빠른 무리가 전체의 12~75%
     *  - 45초 이상 이어진 빠른 구간이 2번 이상 (짧은 느린 틈 15초 미만은 이어진 것으로 본다)
     */
    internal fun intervalThreshold(sm: DoubleArray, t: LongArray): Double? {
        val n = sm.size
        if (n < 30) return null
        val w = DoubleArray(n) { i ->
            if (i < n - 1) ((t[i + 1] - t[i]) / 1000.0).let { if (it > 20.0) 1.0 else it } else 1.0
        }
        val lo = 1.0
        val hi = 7.0
        val bins = 60
        val width = (hi - lo) / bins
        val hist = DoubleArray(bins)
        var total = 0.0
        for (i in 0 until n) {
            if (sm[i] >= lo) {
                hist[((sm[i] - lo) / width).toInt().coerceIn(0, bins - 1)] += w[i]
                total += w[i]
            }
        }
        if (total < 300.0) return null
        var sumAll = 0.0
        for (b in 0 until bins) sumAll += (lo + (b + 0.5) * width) * hist[b]
        var wB = 0.0
        var sumB = 0.0
        var best = -1.0
        var bestBin = -1
        for (b in 0 until bins - 1) {
            wB += hist[b]
            sumB += (lo + (b + 0.5) * width) * hist[b]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF <= 0.0) break
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                bestBin = b
            }
        }
        if (bestBin < 0) return null
        val thr = lo + (bestBin + 1) * width
        if (thr < 2.6) return null

        var lowW = 0.0
        var lowS = 0.0
        var highW = 0.0
        var highS = 0.0
        for (i in 0 until n) {
            if (sm[i] < lo) continue
            if (sm[i] >= thr) { highW += w[i]; highS += sm[i] * w[i] } else { lowW += w[i]; lowS += sm[i] * w[i] }
        }
        if (lowW <= 0.0 || highW <= 0.0) return null
        val mLow = lowS / lowW
        val mHigh = highS / highW
        val frac = highW / (highW + lowW)
        if (mHigh < mLow * 1.25 || mHigh - mLow < 0.7 || frac < 0.12 || frac > 0.75) return null

        class St(val fast: Boolean, var dur: Double)
        val st = ArrayList<St>()
        for (i in 0 until n) {
            val f = sm[i] >= thr
            if (st.isEmpty() || st.last().fast != f) st += St(f, w[i]) else st.last().dur += w[i]
        }
        var k = 1
        while (k < st.size - 1) {
            if (!st[k].fast && st[k].dur < 15.0 && st[k - 1].fast && st[k + 1].fast) {
                st[k - 1].dur += st[k].dur + st[k + 1].dur
                st.removeAt(k + 1)
                st.removeAt(k)
            } else k++
        }
        return if (st.count { it.fast && it.dur >= 45.0 } >= 2) thr else null
    }

    /** speed: (시각 ms, m/s), hr: (시각 ms, bpm), cad: (시각 ms, 분당 걸음 수) 목록 */
    fun analyze(
        speed: List<Pair<Long, Double>>,
        hr: List<Pair<Long, Double>>,
        cad: List<Pair<Long, Double>> = emptyList(),
    ): Result {
        val sp = speed.sortedBy { it.first }
        val n = sp.size
        if (n < 30) return empty
        val t = LongArray(n) { sp[it].first }
        val v = DoubleArray(n) { sp[it].second }

        val hrSorted = hr.sortedBy { it.first }
        val hrT = LongArray(hrSorted.size) { hrSorted[it].first }
        val hrV = DoubleArray(hrSorted.size) { hrSorted[it].second }
        fun hrAvg(from: Long, to: Long): Int? {
            var sum = 0.0
            var c = 0
            for (i in hrT.indices) {
                if (hrT[i] in from..to) {
                    sum += hrV[i]
                    c++
                }
            }
            return if (c > 0) (sum / c).toInt() else null
        }
        val cadList = cad.filter { it.second > 0.0 }.sortedBy { it.first }
        fun cadAvg(from: Long, to: Long): Int? {
            var sum = 0.0
            var c = 0
            for (p in cadList) {
                if (p.first in from..to) {
                    sum += p.second
                    c++
                }
            }
            return if (c > 0) (sum / c).toInt() else null
        }

        // 1) 속도 평활화(약 ±4초) 후 종류 분류 (인터벌이면 경계 속도를 '달리기' 기준으로)
        val gaps = DoubleArray(n - 1) { (t[it + 1] - t[it]) / 1000.0 }.sorted()
        val medDt = gaps[gaps.size / 2].coerceAtLeast(0.5)
        val half = maxOf(1, Math.round(4.0 / medDt).toInt())   // 1초 간격이면 ±4칸, 5초 간격이면 ±1칸
        val sm = DoubleArray(n) { i ->
            val lo = maxOf(0, i - half)
            val hi = minOf(n - 1, i + half)
            var sum = 0.0
            for (k in lo..hi) sum += v[k]
            sum / (hi - lo + 1)
        }
        val runMin = intervalThreshold(sm, t) ?: RUN_MIN

        // 케이던스·심박을 타임스탬프 기준으로 보간
        fun interpVal(src: List<Pair<Long, Double>>, ts: Long): Double? {
            if (src.isEmpty()) return null
            val idx = src.indexOfFirst { it.first >= ts }
            return when {
                idx < 0 -> src.last().second
                idx == 0 -> src.first().second
                else -> {
                    val a = src[idx - 1]; val b = src[idx]
                    val f = (ts - a.first).toDouble() / (b.first - a.first)
                    a.second + f * (b.second - a.second)
                }
            }
        }
        val cadSorted = cadList  // 이미 정렬됨
        val hrSortedPairs = hrSorted.map { it.first to it.second.toDouble() }

        val kinds = Array(n) {
            kindOf(
                v     = sm[it],
                runMin = runMin,
                cadence = interpVal(cadSorted.map { p -> p.first to p.second }, t[it]),
                hr      = interpVal(hrSortedPairs, t[it]),
            )
        }

        // 2) 같은 종류끼리 묶기
        val raw = mutableListOf<Raw>()
        var st = 0
        for (i in 1..n) {
            if (i == n || kinds[i] != kinds[st]) {
                raw += Raw(kinds[st], st, i - 1)
                st = i
            }
        }

        // 3) 30초 미만의 짧은 구간은 더 긴 이웃에 흡수
        fun dur(r: Raw): Double = (t[r.endIdx] - t[r.startIdx]) / 1000.0 + 1.0
        while (raw.size > 1) {
            // 90초 미만 짧은 구간 흡수 — 슬로우 조깅 중 일시적 걷기 구간 제거
            val i = raw.indexOfFirst { dur(it) < 90.0 }
            if (i < 0) break
            val left = if (i > 0) raw[i - 1] else null
            val right = if (i < raw.size - 1) raw[i + 1] else null
            val target = when {
                left == null -> right!!
                right == null -> left
                dur(left) >= dur(right) -> left
                else -> right
            }
            raw[i].kind = target.kind
            var j = 0
            while (j < raw.size - 1) {
                if (raw[j].kind == raw[j + 1].kind) {
                    raw[j].endIdx = raw[j + 1].endIdx
                    raw.removeAt(j + 1)
                } else {
                    j++
                }
            }
        }

        val firstRun = raw.indexOfFirst { it.kind == SegKind.RUN }
        val lastRun = raw.indexOfLast { it.kind == SegKind.RUN }
        if (firstRun < 0) return empty

        // 움직인 시간(초): 정지 구간은 뺀다
        val mov = DoubleArray(n)
        for (i in 1 until n) {
            var dt = (t[i] - t[i - 1]) / 1000.0
            if (dt > 20.0) dt = 1.0
            mov[i] = mov[i - 1] + (if (kinds[i - 1] == SegKind.REST) 0.0 else dt)
        }

        // 4) 구간별 거리/시간/심박/케이던스
        val tStart = t[0]
        val segments = raw.mapIndexed { idx, r ->
            val role = when {
                idx < firstRun -> SegRole.WARMUP
                idx > lastRun -> SegRole.COOLDOWN
                r.kind == SegKind.RUN -> SegRole.MAIN
                else -> SegRole.RECOVERY
            }
            val endInt = if (idx < raw.size - 1) raw[idx + 1].startIdx else r.endIdx
            var d = 0.0
            for (i in r.startIdx + 1..endInt) {
                var dt = (t[i] - t[i - 1]) / 1000.0
                if (dt > 20.0) dt = 1.0
                d += v[i - 1] * dt
            }
            RunSegment(
                role = role,
                kind = r.kind,
                startSec = (t[r.startIdx] - tStart) / 1000,
                durSec = (t[endInt] - t[r.startIdx]) / 1000,
                distanceKm = d / 1000.0,
                avgHr = hrAvg(t[r.startIdx], t[endInt]),
                cadence = cadAvg(t[r.startIdx], t[r.endIdx]),
            )
        }

        // 5) 본 운동(첫 달리기 ~ 마지막 달리기) 안의 1km 구간 (정지 시간 제외)
        val mainStart = raw[firstRun].startIdx
        val mainEnd = raw[lastRun].endIdx
        var cum = 0.0
        var next = 1000.0
        var prevMov = mov[mainStart]
        val splits = mutableListOf<Int>()
        for (i in mainStart + 1..mainEnd) {
            var dt = (t[i] - t[i - 1]) / 1000.0
            if (dt > 20.0) dt = 1.0
            val seg = v[i - 1] * dt
            if (seg > 0 && cum + seg >= next) {
                val frac = (next - cum) / seg
                val mCross = mov[i - 1] + frac * (mov[i] - mov[i - 1])
                splits += Math.round(mCross - prevMov).toInt()
                prevMov = mCross
                next += 1000.0
            }
            cum += seg
        }

        return Result(
            splits = splits,
            mainKm = cum / 1000.0,
            mainSec = Math.round(mov[mainEnd] - mov[mainStart]),
            avgHr = hrAvg(t[mainStart], t[mainEnd]),
            cadence = cadAvg(t[mainStart], t[mainEnd]),
            segments = segments,
        )
    }
}
