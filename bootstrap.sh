#!/bin/sh
# Builds jpm with plain javac, then lets jpm build itself into dist/.
set -eu
cd "$(dirname "$0")"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAR_OUT=.cache/bootstrap/jpm.jar
rm -rf .cache/bootstrap
mkdir -p .cache/bootstrap/classes
find src -name '*.java' > .cache/bootstrap/sources.txt
"$JAVAC" --release 21 -encoding UTF-8 -d .cache/bootstrap/classes @.cache/bootstrap/sources.txt
printf 'Main-Class: dev.jpm.cli.Main\n' > .cache/bootstrap/manifest.txt
"${JAVA_HOME:+$JAVA_HOME/bin/}jar" --create --file "$JAR_OUT" --manifest .cache/bootstrap/manifest.txt -C .cache/bootstrap/classes .
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$JAR_OUT" build
echo "Done. Put $(pwd)/bin on your PATH to use jpm."
