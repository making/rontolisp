#!/bin/bash
# size.sh File.java...: the wasm bytes of each program under two class directories, BEFORE
# (a copy of target/classes taken before the change) and AFTER (target/classes of the work
# tree, with the library's resources copied in). The work files go under a temporary directory.
BEFORE=$(realpath "${BEFORE:?the classes directory to compare against}")
AFTER=$(realpath "${AFTER:-target/classes}")
o=$(mktemp -d)
for f in "$@"; do
  b=$(basename "$f" .java)
  java -cp "$BEFORE" am.ik.rontolisp.cli.RontoLispCli "$f" -o "$o/$b-before.wasm" 2>&1 | head -3
  java -cp "$AFTER" am.ik.rontolisp.cli.RontoLispCli "$f" -o "$o/$b-after.wasm" 2>&1 | head -3
  echo "$b: $(stat -c %s "$o/$b-before.wasm") -> $(stat -c %s "$o/$b-after.wasm")"
done
