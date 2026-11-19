#!/usr/bin/env bash
# size.sh File.java .. : the bytes of the class file and of the .wasm a program compiles
# to. JAR is the exec jar; with OLD too, of that jar beside it.
: "${JAR:?the exec jar of this tree}"
for src in "$@"; do
  src=$(readlink -f "$src"); name=$(basename "$src" .java); line="$name:"
  for which in OLD JAR; do
    [ -n "${!which}" ] || continue
    w=$(mktemp -d)
    (cd "$w" && java -jar "${!which}" "$src" -o "$name.class" --class-name "$name" > c.log 2>&1; java -jar "${!which}" "$src" -o p.wasm > w.log 2>&1)
    line="$line $which class=$(stat -c %s "$w/$name.class" 2>/dev/null || echo fail) wasm=$(stat -c %s "$w/p.wasm" 2>/dev/null || echo fail)"
  done
  echo "$line"
done
