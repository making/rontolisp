#!/bin/bash
# mkjar.sh BASE.jar NAME: a comparison jar in seconds instead of a package run -- the worktree's
# main sources compiled by plain javac (every error into $WORK/jc.log, the count printed), their
# classes put over a copy of BASE.jar (a packaged -exec jar) as $WORK/NAME.jar. Make both sides
# of a comparison this way from the same BASE.jar and not even the version strings differ.
# ROOT is the repository (default: the current directory); $WORK/cp.txt is the dependency
# classpath (a85's jc.sh makes it).
ROOT=${ROOT:-$(pwd)}
WORK=${WORK:-/tmp/a87-work}
mkdir -p "$WORK"
if [ ! -s "$WORK/cp.txt" ]; then
  (cd "$ROOT" && ./mvnw -q dependency:build-classpath -Dmdep.outputFile="$WORK/cp.txt" > /dev/null)
fi
rm -rf "$WORK/jc-out"
mkdir -p "$WORK/jc-out"
find "$ROOT/src/main/java" -name '*.java' > "$WORK/srcs.txt"
javac -nowarn -Xmaxerrs 5000 --add-modules jdk.incubator.vector -proc:none -cp "$(cat "$WORK/cp.txt")" \
  -d "$WORK/jc-out" @"$WORK/srcs.txt" 2>&1 | grep -v "^Note:\|incubat" > "$WORK/jc.log"
errors=$(grep -c "error:" "$WORK/jc.log")
echo "$errors javac errors"
[ "$errors" -eq 0 ] || exit 1
cp "$1" "$WORK/$2.jar"
(cd "$WORK/jc-out" && jar uf "$WORK/$2.jar" am)
ls -la "$WORK/$2.jar"
