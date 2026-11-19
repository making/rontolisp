#!/bin/bash
# size.sh File.java...: the wasm bytes of each program under two builds, BEFORE the exec
# jar of the commit compared against and AFTER the classes directory of the work tree
# (target/classes, with the library's resources copied in). The work files go under TMPDIR.
BEFORE=$(realpath "${BEFORE:?the exec jar to compare against}")
AFTER=$(realpath "${AFTER:-target/classes}")
o=$(mktemp -d)
for f in "$@"; do
  b=$(basename "$f" .java)
  java -jar "$BEFORE" "$f" -o "$o/$b-before.wasm" 2>/dev/null
  java -cp "$AFTER" am.ik.rontolisp.cli.RontoLispCli "$f" -o "$o/$b-after.wasm" 2>&1 | head -3
  echo "$b: $(stat -c %s "$o/$b-before.wasm") -> $(stat -c %s "$o/$b-after.wasm")"
done
