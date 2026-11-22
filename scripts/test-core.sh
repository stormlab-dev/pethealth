#!/usr/bin/env bash
# Runs the :core unit tests (JVM) in a throwaway build that needs no Android SDK or Google Maven
# (useful on CI runners or machines without Android tooling). Normal dev: ./gradlew :core:test
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="${TMPDIR:-/tmp}/petpulse-core-build"
rm -rf "$TMP/core/src" && mkdir -p "$TMP"
cp -r "$ROOT/core" "$ROOT/gradle" "$ROOT/gradlew" "$ROOT/gradle.properties" "$TMP/"
cat > "$TMP/settings.gradle.kts" <<'KTS'
pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "petpulse-core"
include(":core")
KTS
echo "" > "$TMP/build.gradle.kts"
cd "$TMP" && ./gradlew :core:jvmTest --console=plain "$@"
