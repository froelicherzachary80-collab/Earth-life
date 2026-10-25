#!/usr/bin/env bash
# Builds and tests the dependency-free modules (core + launcher) with only a JDK 21+.
# The Fabric mod needs Gradle + network access to maven.fabricmc.net: see README.md section 3.
set -euo pipefail
cd "$(dirname "$0")"
OUT=build
rm -rf "$OUT"; mkdir -p "$OUT"/{core,core-test,launcher,launcher-test}

echo "== core"
javac -Xlint:all -Xlint:-serial -d "$OUT/core" $(find core/src/main/java -name '*.java')
javac -d "$OUT/core-test" -cp "$OUT/core" $(find core/src/test/java -name '*.java')
(cd core && java -Dearthlife.data=../data -cp "../$OUT/core:../$OUT/core-test" earthlife.test.T)

echo "== launcher"
javac -Xlint:all -Xlint:-serial -d "$OUT/launcher" -cp "$OUT/core" $(find launcher/src/main/java -name '*.java')
javac -d "$OUT/launcher-test" -cp "$OUT/core:$OUT/launcher" $(find launcher/src/test/java -name '*.java')
java -cp "$OUT/core:$OUT/launcher:$OUT/launcher-test" earthlife.launcher.test.LauncherTest launcher/src/test/resources

echo "== data pipeline"
python3 tools/test_pipeline.py

echo "== package"
mkdir -p dist
jar --create --file dist/earthlife-core.jar -C "$OUT/core" .
cp -r data "$OUT/launcher/earthlife-data" 2>/dev/null || true
jar --create --file dist/earthlife-launcher.jar --main-class earthlife.launcher.Main -C "$OUT/launcher" . -C "$OUT/core" .
echo "Built dist/earthlife-core.jar and dist/earthlife-launcher.jar"
