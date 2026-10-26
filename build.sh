#!/usr/bin/env bash
# Builds and tests the dependency-free core engine with only a JDK 21+ (no network needed).
# The Fabric mod needs Gradle + network access to maven.fabricmc.net: see README.md section 3.
set -euo pipefail
cd "$(dirname "$0")"
OUT=build
rm -rf "$OUT"; mkdir -p "$OUT"/{core,core-test}

echo "== core"
javac -Xlint:all -Xlint:-serial -d "$OUT/core" $(find core/src/main/java -name '*.java')
javac -d "$OUT/core-test" -cp "$OUT/core" $(find core/src/test/java -name '*.java')
(cd core && java -Dearthlife.data=../data -cp "../$OUT/core:../$OUT/core-test" earthlife.test.T)

echo "== data pipeline"
python3 tools/test_pipeline.py

echo "== package"
mkdir -p dist
jar --create --file dist/earthlife-core.jar -C "$OUT/core" .
echo "Built dist/earthlife-core.jar (the Fabric mod itself is built by Gradle / GitHub Actions)"
