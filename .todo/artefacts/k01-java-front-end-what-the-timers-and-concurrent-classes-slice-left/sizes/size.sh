#!/usr/bin/env bash
# size.sh File.java .. : the bytes of the class files and of the .wasm a program compiles
# to, with the jar of one tree (OLD) and of another (NEW).
: "${OLD:?the exec jar of the tree to compare with}" "${NEW:?the exec jar of this tree}"
for src in "$@"; do
  src=$(readlink -f "$src"); line="$(basename "$src" .java):"
  for which in OLD NEW; do
    w=$(mktemp -d)
    (cd "$w" && java -jar "${!which}" "$src" -o Prog.class > c.log 2>&1; java -jar "${!which}" "$src" -o p.wasm > w.log 2>&1)
    line="$line $which class=$(cat "$w"/*.class 2>/dev/null | wc -c) wasm=$(stat -c %s "$w/p.wasm" 2>/dev/null || echo fail)"
  done
  echo "$line"
done
