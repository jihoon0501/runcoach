#!/usr/bin/env bash
# 훈련 계획 엔진 시뮬레이션. kotlinc(Kotlin 2.x)와 java(JDK 17 이상)가 PATH에 있어야 해요.
# kotlin-stdlib.jar 위치를 못 찾으면 KOTLIN_STDLIB 환경변수로 직접 알려 주세요.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
APP="$ROOT/app/src/main/java/io/github/jihoon0501/runcoach"
OUT="${TMPDIR:-/tmp}/runcoach-plan-sim"
rm -rf "$OUT" && mkdir -p "$OUT"

kotlinc "$APP/PlanEngine.kt" "$APP/Data.kt" "$APP/FatigueMath.kt" "$APP/RunAnalyzer.kt" \
        "$APP/PaceGradient.kt" "$APP/DataSources.kt" "$HERE"/*.kt -d "$OUT"

KC="$(readlink -f "$(command -v kotlinc)")"
STDLIB="${KOTLIN_STDLIB:-$(dirname "$(dirname "$KC")")/lib/kotlin-stdlib.jar}"
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$OUT:$STDLIB" io.github.jihoon0501.runcoach.HarnessKt
