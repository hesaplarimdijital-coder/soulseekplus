#!/usr/bin/env bash
# Demo ağını (core test kaynakları) Gradle'ın gömülü Kotlin derleyicisiyle derler ve çalıştırır.
# Kullanım: tools/demo-local.sh [--selftest]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
L=/opt/gradle/lib
OUT="${DEMO_OUT:-$(mktemp -d "${TMPDIR:-/tmp}/soulseekplus-demo.XXXXXX")}"
mkdir -p "$OUT/main" "$OUT/test"
COMPILER_CP="$(ls $L/kotlin-compiler-embeddable-*.jar $L/kotlin-stdlib-2*.jar $L/kotlin-daemon-embeddable-*.jar \
  $L/kotlin-reflect-*.jar $L/annotations-*.jar $L/trove4j-*.jar $L/kotlin-script-runtime-*.jar $L/kotlinx-coroutines-core-jvm-*.jar | tr '\n' ':')"
STDLIB="$(ls $L/kotlin-stdlib-2*.jar)"
COROUTINES="$(ls $L/kotlinx-coroutines-core-jvm-*.jar)"
JUNIT="$(ls $L/junit-4*.jar):$(ls $L/hamcrest-core-*.jar 2>/dev/null || ls $L/hamcrest-*.jar | head -1)"
kc() { java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -jvm-target 17 "$@" 2>&1 | grep -v JAVA_TOOL_OPTIONS || true; }
kc -cp "$STDLIB:$COROUTINES" -d "$OUT/main" $(find "$ROOT/core/src/main/kotlin" -name '*.kt')
kc -cp "$STDLIB:$COROUTINES:$JUNIT:$OUT/main" -d "$OUT/test" $(find "$ROOT/core/src/test/kotlin" -name '*.kt')
exec java -cp "$STDLIB:$COROUTINES:$OUT/main:$OUT/test" com.soulseekplus.core.engine.DemoNetworkKt "$@"
