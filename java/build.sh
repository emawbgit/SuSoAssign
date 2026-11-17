#!/bin/sh
# Build susoassign.jar (Java 11 bytecode) and run the tests.
#   JAVA_HOME  JDK 11+ (Stata ships one: <Stata>/utilities/java/<platform>/zulu-jdk*)
#   SFI_JAR    Stata's sfi-api.jar (<Stata>/utilities/jar/sfi-api.jar); without it the
#              Stata bridge is skipped and only the core is compiled and tested (CI mode).
set -eu
cd "$(dirname "$0")"
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAR="${JAVA_HOME:+$JAVA_HOME/bin/}jar"
rm -rf build && mkdir -p build/classes build/test
SRC=$(find src -name '*.java')
if [ -n "${SFI_JAR:-}" ]; then
  "$JAVAC" --release 11 -encoding UTF-8 -cp "$SFI_JAR" -d build/classes $SRC
  "$JAR" --create --file ../susoassign.jar -C build/classes .
  echo "built ../susoassign.jar"
else
  "$JAVAC" --release 11 -encoding UTF-8 -d build/classes $(echo "$SRC" | tr ' ' '\n' | grep -v Bridge.java)
fi
"$JAVAC" --release 11 -encoding UTF-8 -cp build/classes -d build/test $(find test -name '*.java')
"$JAVA" -ea -cp "build/classes${SEP}build/test" org.emawbgit.susoassign.TestAll ../tests/fixtures
