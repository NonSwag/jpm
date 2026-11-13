#!/bin/sh
# Builds the native jpm binary with GraalVM native-image (roadmap: jpm will do this itself, step 2).
set -eu
: "${JPM_JAR:?run this through 'jpm run native' after 'jpm build'}"
NI="${GRAALVM_HOME:-${JAVA_HOME:-}}/bin/native-image"
[ -x "$NI" ] || NI=native-image
mkdir -p dist
"$NI" --no-fallback -O2 -H:+ReportExceptionStackTraces \
  --enable-url-protocols=https,http \
  -jar "$JPM_JAR" -o dist/jpm
echo "Built dist/jpm"
