#!/bin/bash
# runchunks.sh <jarA> <jarB> <programs-dir> <n>: Cmp.java over the programs in n parallel JVMs
# (extract.py makes the directory); prints every difference, then one summary line per chunk.
T=$(cd "$(dirname "$0")" && pwd)
WORK=${WORK:-/tmp/a85-work}
ja=$(realpath "$1"); jb=$(realpath "$2"); progs=$(realpath "$3")
mkdir -p "$WORK"
cd "$WORK" || exit 1
rm -rf chunks
mkdir chunks
i=0
for f in $(ls "$progs"); do
  d=chunks/c$((i % $4))
  mkdir -p "$d"
  cp "$progs/$f" "$d/"
  i=$((i + 1))
done
for d in chunks/c*; do
  java -Xss512m "$T/Cmp.java" "$ja" "$jb" "$d" > "$d.out" 2>&1 &
done
wait
grep -h "^DIFF\|^FAIL-MIS\|^CLASS-SET" chunks/*.out
grep -h "^programs" chunks/*.out
