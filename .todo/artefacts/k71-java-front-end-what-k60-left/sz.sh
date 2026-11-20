#!/bin/bash
# sz.sh A.jar B.jar File.java...: the wasm bytes of each program under two exec jars, four
# compiles at a time; the modules go to $SZ_OUT (a new temporary directory by default)
A=$1; B=$2; shift 2
o=${SZ_OUT:-$(mktemp -d)}
printf '%s\n' "$@" | xargs -P 4 -I{} sh -c 'b=$(basename "{}" .java); java -jar "$0" "{}" -o "$2/$b-a.wasm" >/dev/null 2>&1; java -jar "$1" "{}" -o "$2/$b-b.wasm" >/dev/null 2>&1' "$A" "$B" "$o"
for f in "$@"; do
  b=$(basename "$f" .java)
  echo "$b $(stat -c %s "$o/$b-a.wasm" 2>/dev/null) -> $(stat -c %s "$o/$b-b.wasm" 2>/dev/null)"
done
