#!/bin/bash
# rediff.sh <jarA> <jarB>: re-runs the programs runchunks.sh reported as DIFF, which dumps
# their class pairs into $WORK/diffs, and prints each pair's method diff.
T=$(cd "$(dirname "$0")" && pwd)
WORK=${WORK:-/tmp/a85-work}
ja=$(realpath "$1"); jb=$(realpath "$2")
cd "$WORK" || exit 1
rm -rf diffs sub
mkdir sub
for f in $(grep -h "^DIFF" chunks/*.out | awk '{print $2}'); do cp "$f" sub/; done
java -Xss512m "$T/Cmp.java" "$ja" "$jb" sub 2>&1 | grep -v warning | tail -3
for a in diffs/*.A.class; do
  b="${a%.A.class}.B.class"
  echo "== $a"
  java "$T/MethodDiff.java" "$a" "$b" | head -12
done
