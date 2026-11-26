#!/usr/bin/env bash
# sizes.sh OUT-DIR: compiles the programs beside it, examples/java/shop and Ladder over
# jdbc-postgresql/ to wasm, a component and (the five) a jar; prints each output's size and
# the start of its sha256. Run before and after a change: a program naming none of what
# changed has to print the same line.
set -u
here=$(cd "$(dirname "$0")" && pwd)
W=$(cd "$here/../../../.." && pwd)
CP=${CLASSES:-$W/target/classes}
out=$1; mkdir -p "$out"; out=$(cd "$out" && pwd)
run() { java -Xss512m -cp "$CP" am.ik.rontolisp.cli.RontoLispCli "$@"; }
for p in SbBase LocBase FjBase CollBase UpdBase; do
  (cd "$here" && run $p.java -o "$out/$p.wasm" > "$out/$p.wasm.log" 2>&1; run $p.java -o "$out/$p.c.wasm" --component > "$out/$p.c.log" 2>&1; run $p.java -o "$out/$p.jar" > "$out/$p.jar.log" 2>&1) &
done
(cd "$W/examples/java/shop" && run Main.java -o "$out/shop.wasm" > "$out/shop.log" 2>&1; run Main.java -o "$out/shop.c.wasm" --component > "$out/shop.c.log" 2>&1) &
SP=$W/jdbc-postgresql/src/main/java:$W/jdbc-postgresql/src/main/resources
(cd "$here" && run --java-source-path "$SP" Ladder.java -o "$out/ladder-jp.c.wasm" --component > "$out/ladder.c.log" 2>&1; run --java-source-path "$SP" Ladder.java -o "$out/ladder-jp.wasm" > "$out/ladder.log" 2>&1) &
wait
cd "$out" && for f in *.wasm *.jar; do printf '%s %s %s\n' "$f" "$(stat -c %s "$f")" "$(sha256sum "$f" | cut -c1-16)"; done | sort
