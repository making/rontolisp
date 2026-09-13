#!/bin/bash
# usage: measure-print.sh <outdir> -- compiles hello/report (--no-gc, WASI on, size) with the
# worktree jar, prints sizes, runs under wasmtime and diffs against the interpreter.
set -e
P="$(cd "$(dirname "$0")" && pwd)"
J=${JAR:?set JAR to a rontolisp-0.1.0-SNAPSHOT-exec.jar}
O=$P/$1
mkdir -p $O
cd $P
java -jar $J hello-interp.lisp > $O/hello-interp.txt
java -jar $J report-interp.lisp > $O/report-interp.txt
for p in hello report; do
  for lv in off size; do
    java -jar $J $p.lisp -o $O/$p-$lv.wasm --no-gc --optimize=$lv
    echo "$p $lv raw=$(wc -c < $O/$p-$lv.wasm | tr -d ' ') gz=$(gzip -9 -c < $O/$p-$lv.wasm | wc -c | tr -d ' ')"
    node ./sections.mjs $O/$p-$lv.wasm | sed -n 2p
    node ./dsect.mjs $O/$p-$lv.wasm | grep 'data payload'
    if [ $p = hello ]; then wasmtime run --invoke main $O/$p-$lv.wasm > $O/$p-$lv.txt; else wasmtime run --invoke main $O/$p-$lv.wasm 3 > $O/$p-$lv.txt; fi
    if diff $O/$p-interp.txt $O/$p-$lv.txt > /dev/null; then echo "  $p $lv: IDENTICAL to interpreter"; else echo "  $p $lv: DIFFERS"; diff $O/$p-interp.txt $O/$p-$lv.txt || true; fi
  done
done
node ../810-no-gc-dead-literal-length-headers/probe/dumpdata.mjs $O/report-size.wasm | head -2 | cut -c1-400
wasm-tools print $O/hello-size.wasm | sed -n '/(func (;1;)/,/^  )/p' | head -30
