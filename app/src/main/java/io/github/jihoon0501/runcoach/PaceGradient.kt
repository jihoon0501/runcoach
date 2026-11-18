package io.github.jihoon0501.runcoach

/**
 * 페이스(속도)에 따라 색이 연속적으로 변하는 색 눈금. 안드로이드에 의존하지 않는 순수 코드라서 시험할 수 있다.
 * 느릴수록 파랑·청록, 빨라질수록 초록 → 라임 → 노랑 → 주황 → 분홍. 색상 값은 0xAARRGGBB 정수다.
 */
object PaceGradient {

    /** (속도 m/s, 0xRRGGBB). 사이 값은 색 성분별로 직선으로 이어 붙인다 */
    private val STOPS = listOf(
        0.0 to 0x4A5578,   // 거의 멈춤
        1.0 to 0x3F7CFF,   // 느린 걷기 (16'40"/km)
        1.9 to 0x2EC4D6,   // 걷기와 조깅의 경계 (8'46")
        2.3 to 0x34D399,   // 가벼운 조깅 (7'15")
        2.8 to 0x86E34A,   // 이지런 (5'57")
        3.2 to 0xC8FF3E,   // 꾸준한 러닝 (5'12")
        3.6 to 0xFFE03A,   // 빠른 러닝 (4'38")
        4.0 to 0xFF9F43,   // 인터벌 (4'10")
        4.5 to 0xFF6B57,   // 매우 빠름 (3'42")
        5.2 to 0xFF3D8B,   // 전력 질주 (3'12")
    )

    const val SLOW_SPEED = 1000.0 / 540.0   // 9'00"/km: 범례의 왼쪽(느림) 끝
    const val FAST_SPEED = 1000.0 / 240.0   // 4'00"/km: 범례의 오른쪽(빠름) 끝

    /** 속도(m/s)에 해당하는 색. 범위 밖은 양 끝 색으로 고정한다 */
    fun argb(speed: Double): Int {
        val v = if (speed.isNaN()) 0.0 else speed
        if (v <= STOPS.first().first) return 0xFF000000.toInt() or STOPS.first().second
        if (v >= STOPS.last().first) return 0xFF000000.toInt() or STOPS.last().second
        var i = 0
        while (v > STOPS[i + 1].first) i++
        val (v0, c0) = STOPS[i]
        val (v1, c1) = STOPS[i + 1]
        val f = (v - v0) / (v1 - v0)
        fun ch(shift: Int): Int {
            val a = (c0 shr shift) and 0xFF
            val b = (c1 shr shift) and 0xFF
            return Math.round(a + (b - a) * f).toInt().coerceIn(0, 255)
        }
        return 0xFF000000.toInt() or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * 구간들을 가로로 이어 붙인 막대의 그라데이션 눈금 (위치 0~1, 색). 각 구간은 가운데 부분이 자기 색이고,
     * 경계에서는 이웃 구간의 색과 부드럽게 섞인다. [maxBlend]는 경계 한쪽의 섞이는 폭(막대 전체 대비 비율)이다.
     */
    fun barStops(durations: List<Long>, speeds: List<Double>, maxBlend: Float = 0.03f): List<Pair<Float, Int>> {
        if (durations.isEmpty() || durations.size != speeds.size) return emptyList()
        val total = durations.sumOf { it.coerceAtLeast(1L) }.toFloat()
        val out = ArrayList<Pair<Float, Int>>()
        var acc = 0f
        var prev = 0f
        for (i in durations.indices) {
            val w = durations[i].coerceAtLeast(1L) / total
            val a = acc
            val b = acc + w
            val c = argb(speeds[i])
            val bl = minOf(w / 2f, maxBlend)
            val p1 = (if (i == 0) 0f else a + bl).coerceIn(prev, 1f)
            val p2 = (if (i == durations.lastIndex) 1f else b - bl).coerceIn(p1, 1f)
            out += p1 to c
            out += p2 to c
            prev = p2
            acc = b
        }
        return out
    }

    /** 범례 막대용: 느림 끝에서 빠름 끝까지 [n]개 지점의 (위치 0~1, 색) */
    fun legendStops(n: Int = 8): List<Pair<Float, Int>> =
        (0 until n).map { i ->
            val f = i / (n - 1).toFloat()
            f to argb(SLOW_SPEED + (FAST_SPEED - SLOW_SPEED) * f)
        }
}
