#!/bin/bash
# sz.sh OLDJAR NEWJAR File.java... : the wasm (preview 1) bytes of each program under two exec jars
OLD=$1; NEW=$2; shift 2
OUT=${SZ_OUT:-$(mktemp -d)}
for f in "$@"; do
  a=$(java -jar "$OLD" "$f" -o "$OUT/old.wasm" >/dev/null 2>&1 && stat -c %s "$OUT/old.wasm")
  b=$(java -jar "$NEW" "$f" -o "$OUT/new.wasm" >/dev/null 2>&1 && stat -c %s "$OUT/new.wasm")
  echo "$f $a -> $b"
done
