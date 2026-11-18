package io.github.jihoon0501.runcoach

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** 차트 눈금과 터치 위치 계산. 안드로이드에 의존하지 않는 순수 코드라서 시험할 수 있다. */
object ChartMath {

    /** [lo]~[hi] 범위와 눈금 값들. [frac]은 값을 0(맨 아래)~1(맨 위)로 바꾼다 */
    class Axis(val lo: Float, val hi: Float, val step: Float, val ticks: List<Float>) {
        fun frac(v: Float): Float = if (hi > lo) ((v - lo) / (hi - lo)).coerceIn(0f, 1f) else 0.5f

        /** 눈금이 정수 간격이면 소수점 없이, 아니면 소수 한 자리로 */
        fun label(v: Float): String =
            if (abs(step - Math.round(step).toFloat()) < 1e-4f) "%.0f".format(v) else "%.1f".format(v)
    }

    /**
     * 데이터 범위를 덮는 '깔끔한' 눈금 (1, 2, 2.5, 5, 10 × 10의 거듭제곱 간격). 눈금은 최대 [maxTicks]+1개 안팎이다.
     * [zeroBased]이면 0부터 시작한다 (막대 그래프용).
     */
    fun niceAxis(minV: Float, maxV: Float, maxTicks: Int = 5, zeroBased: Boolean = false): Axis {
        var lo = if (zeroBased) 0f else minV
        var hi = maxV
        if (hi - lo < 1e-6f) {
            if (zeroBased) hi = lo + 1f else { lo -= 0.5f; hi += 0.5f }
        }
        val raw = (hi - lo) / (maxTicks - 1).coerceAtLeast(1)
        val mag = 10.0.pow(floor(log10(raw.toDouble()))).toFloat()
        val step = listOf(1f, 2f, 2.5f, 5f, 10f).map { it * mag }.first { it >= raw - 1e-9f }
        val niceLo = if (zeroBased) 0f else floor(lo / step) * step
        val niceHi = ceil(hi / step) * step
        val n = Math.round((niceHi - niceLo) / step)
        return Axis(niceLo, niceHi, step, (0..n).map { niceLo + it * step })
    }

    /** [xs] 중에서 [x]에 가장 가까운 것의 위치 (비어 있으면 -1) */
    fun nearestIndex(xs: List<Float>, x: Float): Int {
        var best = -1
        var bd = Float.MAX_VALUE
        for (i in xs.indices) {
            val d = abs(xs[i] - x)
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    /**
     * "체지방 -0.2%p" 처럼 항목 이름, 부호 붙은 변화량, 단위를 이어 붙인다.
     * 숫자에만 서식을 적용하고 단위는 뒤에 붙여서, 단위에 '%'가 들어 있어도 서식 오류로 앱이 꺼지지 않는다.
     */
    fun deltaText(label: String, cur: Float, prev: Float, unit: String): String {
        val d = cur - prev
        return label + " " + (if (d >= 0) "+" else "") + "%.1f".format(d) + unit
    }

    // ───────────── 활동 달력(히트맵): 열 = 주(월요일 시작), 행 = 요일(월~일) ─────────────

    /** 그날 러닝 거리를 0(없음)~4(가장 진함) 단계로. [maxKm]은 기간 안의 하루 최대 거리 */
    fun heatLevel(km: Double, maxKm: Double): Int {
        if (km <= 0.0 || maxKm <= 0.0) return 0
        val r = km / maxKm
        return when {
            r < 0.25 -> 1
            r < 0.5 -> 2
            r < 0.75 -> 3
            else -> 4
        }
    }

    /** 가로 [width] 안에 [cols]개 열이 틈 [gap]으로 들어가도록 칸 한 변의 길이를 정한다 */
    fun heatCell(width: Float, cols: Int, gap: Float): Float =
        ((width - gap * (cols - 1)) / cols.coerceAtLeast(1)).coerceAtLeast(2f)

    /** 터치한 위치가 어느 칸(열, 행)인지. 달력 밖이면 null */
    fun heatHit(x: Float, y: Float, cell: Float, gap: Float, cols: Int, rows: Int): Pair<Int, Int>? {
        if (x < 0f || y < 0f) return null
        val step = cell + gap
        val c = (x / step).toInt()
        val r = (y / step).toInt()
        return if (c in 0 until cols && r in 0 until rows) c to r else null
    }

    /** 값이 [lo]~[hi] 범위의 어디쯤인지 0~1 (범위가 없으면 0.5) */
    fun position(v: Float, lo: Float, hi: Float): Float = if (hi - lo < 1e-6f) 0.5f else ((v - lo) / (hi - lo)).coerceIn(0f, 1f)

    /** 날짜(에포크 일수)를 [0, width] 가로 위치로 */
    fun xPositions(days: List<Long>, width: Float): List<Float> {
        if (days.isEmpty()) return emptyList()
        val a = days.first()
        val b = days.last()
        if (b == a) return days.map { width / 2f }
        return days.map { (it - a).toFloat() / (b - a).toFloat() * width }
    }

    /** 막대(슬롯) [n]개에서 [i]번째 슬롯 가운데의 가로 위치 */
    fun slotCenter(i: Int, n: Int, width: Float): Float = (i + 0.5f) * width / n

    /** 터치한 가로 위치가 [n]개 슬롯 중 몇 번째인지 */
    fun slotIndex(x: Float, n: Int, width: Float): Int = if (n <= 0 || width <= 0f) -1 else (x / width * n).toInt().coerceIn(0, n - 1)
}
