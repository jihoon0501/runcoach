import io.github.jihoon0501.runcoach.*
import java.io.File

fun main() {
    val dir = File("/tmp/ktest/data")
    for (f in dir.listFiles()!!.sortedBy { it.name }) {
        val r = ActivityFileReader.read(f.name, f.readBytes())
        if (r.error != null) { println("${f.name}: 오류 → ${r.error}"); continue }
        for (a in r.activities) {
            val run = ActivityConverter.toRun(a)
            val walk = ActivityConverter.toWalk(a)
            println("${f.name}: 종목=${a.sport} 점=${a.points.size} 거리=${a.distanceM?.let { "%.0f".format(it) }}m 시간=${(a.endMs - a.startMs) / 1000}초 평균심박=${a.avgHr} 케이던스원값=${a.avgCadence} 제목=${a.title}")
            if (run != null) println("   → 러닝 %.2fkm 시간 %d초 페이스 %.0f초/km 심박 %s 케이던스 %s 구간 %d개 1km스플릿 %s 본운동 %.2fkm/%d초".format(
                run.distanceKm, run.durationSec, run.paceSecPerKm, run.avgHr, run.cadence, run.segments.size, run.splits, run.mainKm, run.mainSec))
            if (walk != null) println("   → 걷기 %.2fkm %d초 심박 %s".format(walk.distanceKm, walk.durationSec, walk.avgHr))
            if (run == null && walk == null) println("   → (러닝·걷기 아님 또는 너무 짧음)")
        }
    }
}
