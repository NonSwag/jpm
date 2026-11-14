#!/bin/sh
# Builds the native jpm binary with GraalVM native-image. Run it through `jpm run native` after `jpm build`
# (jpm sets JPM_JAR). Needs GRAALVM_HOME or JAVA_HOME pointing at GraalVM, or native-image on PATH.
set -eu
: "${JPM_JAR:?run this through 'jpm run native' after 'jpm build'}"
NI=native-image
for home in "${GRAALVM_HOME:-}" "${JAVA_HOME:-}"; do
  [ -n "$home" ] || continue
  for cand in "$home/bin/native-image" "$home/bin/native-image.cmd"; do
    if [ -x "$cand" ] || [ -f "$cand" ]; then NI="$cand"; break 2; fi
  done
done
mkdir -p dist
# The JDK's XML parser (used for POMs) loads its messages from resource bundles at run time.
"$NI" --no-fallback -O2 \
  -H:+ReportExceptionStackTraces \
  --enable-url-protocols=https,http \
  -H:IncludeResourceBundles=com.sun.org.apache.xerces.internal.impl.msg.XMLMessages \
  -H:IncludeResourceBundles=com.sun.org.apache.xerces.internal.impl.msg.DOMMessages \
  -H:IncludeResourceBundles=com.sun.org.apache.xerces.internal.impl.xpath.regex.message \
  -jar "$JPM_JAR" -o dist/jpm
echo "Built dist/jpm"
