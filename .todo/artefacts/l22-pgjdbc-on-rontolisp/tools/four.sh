#!/usr/bin/env bash
# four.sh FILE.java [extra rontolisp args]: the JDK's lines, then each leg's diff against them.
set -u
here=$(cd "$(dirname "$0")" && pwd)
CP=${CLASSES:-$(cd "$here/../../../.." && pwd)/target/classes}
f=$1; shift; dir=$(cd "$(dirname "$f")" && pwd); stem=$(basename "${f%.java}")
out=$dir/out-$stem; mkdir -p $out
cd $dir
java $stem.java > $out/jdk.txt 2>&1
run() { java -Xss512m -cp $CP am.ik.rontolisp.cli.RontoLispCli "$@"; }
run $stem.java "$@" > $out/int.txt 2>&1 &
(run $stem.java "$@" -o $out/$stem.class --class-name $stem > $out/jvm-compile.txt 2>&1 && cd $out && java $stem > $out/jvm.txt 2>&1) &
(run $stem.java "$@" -o $out/$stem.wasm > $out/wasm-compile.txt 2>&1 && wasmtime run $out/$stem.wasm > $out/wasm.txt 2>&1) &
(run $stem.java "$@" -o $out/$stem.c.wasm --component > $out/comp-compile.txt 2>&1 && wasmtime run $out/$stem.c.wasm > $out/comp.txt 2>&1) &
wait
for leg in int jvm wasm comp; do
  if [ -f $out/$leg.txt ] && diff -q $out/jdk.txt $out/$leg.txt > /dev/null; then echo "$leg: same"; else echo "$leg: DIFFERS"; diff $out/jdk.txt $out/$leg.txt 2>&1 | head -${LINES_SHOWN:-8}; cat $out/*compile.txt 2>/dev/null | head -5; fi
done
