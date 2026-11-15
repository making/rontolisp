#!/bin/bash
# usage: JAR=path/of/rontolisp-exec.jar time.sh file.star|file.lisp ...
# Compiles each program for the JVM, WASM preview 1 and the component, and prints the best
# of three wall times of each (the wasm legs from a precompiled .cwasm, so no Cranelift
# time), the output sizes and the first bytes each leg printed.
JAR=${JAR:?path of the rontolisp exec jar}
for f in "$@"; do
  b=$(basename $f); b=${b%.*}; d=$PWD/out-$b; rm -rf $d; mkdir -p $d
  java -jar $JAR $f -o $d/Prog.class --class-name Prog > $d/jvm.log 2>&1 || { echo "$b: JVM COMPILE FAILED"; tail -3 $d/jvm.log; }
  java -jar $JAR $f -o $d/p.wasm > $d/wasm.log 2>&1 || { echo "$b: WASM COMPILE FAILED"; tail -3 $d/wasm.log; }
  java -jar $JAR $f -o $d/pc.wasm --component > $d/comp.log 2>&1 || { echo "$b: COMPONENT COMPILE FAILED"; tail -3 $d/comp.log; }
  wasmtime compile $d/p.wasm -o $d/p.cwasm 2>/dev/null; wasmtime compile $d/pc.wasm -o $d/pc.cwasm 2>/dev/null
  best() { local m=999; for i in 1 2 3; do s=$(date +%s.%N); "$@" > $d/out.txt 2>&1; e=$(date +%s.%N); t=$(echo "$e - $s" | bc); m=$(echo "if ($t < $m) $t else $m" | bc); done; printf "%.2f" $m; }
  j=$(cd $d && best java -Xss64m Prog); jo=$(head -c 60 $d/out.txt | tr '\n' ' ')
  w=$(cd $d && best wasmtime run --allow-precompiled p.cwasm); wo=$(head -c 60 $d/out.txt | tr '\n' ' ')
  c=$(cd $d && best wasmtime run --allow-precompiled pc.cwasm); co=$(head -c 60 $d/out.txt | tr '\n' ' ')
  echo "$b: jvm $j  wasm $w  comp $c  size $(stat -c %s $d/Prog.class 2>/dev/null)/$(stat -c %s $d/p.wasm 2>/dev/null) | $jo | $wo | $co"
done
