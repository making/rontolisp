#!/bin/bash
# jc.sh: compiles the main source set with plain javac, every error shown (Maven stops at
# 100), into $WORK/jc.log -- the log fix.py and addto.py read. Run from the repository.
ROOT=${ROOT:-$(git rev-parse --show-toplevel)}
WORK=${WORK:-/tmp/a85-work}
mkdir -p "$WORK"
if [ ! -s "$WORK/cp.txt" ]; then
  (cd "$ROOT" && ./mvnw -q dependency:build-classpath -Dmdep.outputFile="$WORK/cp.txt" > /dev/null)
fi
rm -rf "$WORK/jc-out"
mkdir -p "$WORK/jc-out"
find "$ROOT/src/main/java" -name '*.java' > "$WORK/srcs.txt"
javac -nowarn -Xmaxerrs 2000 --add-modules jdk.incubator.vector -proc:none -cp "$(cat "$WORK/cp.txt")" \
  -d "$WORK/jc-out" @"$WORK/srcs.txt" 2>&1 | grep -v "^Note:\|incubat" > "$WORK/jc.log"
grep -c "error:" "$WORK/jc.log"
