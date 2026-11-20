#!/bin/bash
# usage: four.sh JAR OUTDIR compile-args... : each backend's output in OUTDIR/{interp,jvm,p1,comp}.txt, the compiled legs compared with the interpreter's
J=$1; O=$2; shift 2
mkdir -p $O/cls
java -jar $J "$@" > $O/interp.txt 2>&1
java -jar $J "$@" -o $O/cls/Prog.class > $O/jvm.log 2>&1 && (cd $O/cls && java Prog) > $O/jvm.txt 2>&1
java -jar $J "$@" -o $O/p1.wasm > $O/p1.log 2>&1 && wasmtime run $O/p1.wasm > $O/p1.txt 2>&1
java -jar $J "$@" -o $O/comp.wasm --component > $O/comp.log 2>&1 && wasmtime run $O/comp.wasm > $O/comp.txt 2>&1
for b in jvm p1 comp; do cmp -s $O/interp.txt $O/$b.txt && echo "$b same" || { echo "$b DIFFERS"; cat $O/$b.log; diff $O/interp.txt $O/$b.txt | head -10; }; done
ls -la $O/p1.wasm $O/comp.wasm | awk '{print $5, $9}'
