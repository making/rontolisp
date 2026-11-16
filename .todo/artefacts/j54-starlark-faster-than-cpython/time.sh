#!/bin/bash
# usage: JAR=path/of/rontolisp-exec.jar [OTHERS=1] time.sh file.star ...
# (RONTOLISP="command" in place of JAR runs another build of the compiler)
# Compiles each program for the JVM, WASM preview 1 and the component and prints the best
# of three wall times of the whole process for each (the wasm legs from a precompiled
# .cwasm, so no Cranelift time), for python3 and, with OTHERS=1, for Go Starlark
# (~/.local/bin/starlark -recursion) and Java Starlark (~/.local/bin/starlark-java, with
# recursion and sets allowed); then the output sizes and whether every runner printed
# what python3 printed.
R=${RONTOLISP:-java -jar ${JAR:?path of the rontolisp exec jar}}
for f in "$@"; do
  b=$(basename $f); b=${b%.*}; d=$PWD/out-$b; rm -rf $d; mkdir -p $d
  $R $f -o $d/Prog.class --class-name Prog > $d/jvm.log 2>&1 || { echo "$b: JVM COMPILE FAILED"; tail -3 $d/jvm.log; }
  $R $f -o $d/p.wasm > $d/wasm.log 2>&1 || { echo "$b: WASM COMPILE FAILED"; tail -3 $d/wasm.log; }
  $R $f -o $d/pc.wasm --component > $d/comp.log 2>&1 || { echo "$b: COMPONENT COMPILE FAILED"; tail -3 $d/comp.log; }
  wasmtime compile $d/p.wasm -o $d/p.cwasm 2>/dev/null; wasmtime compile $d/pc.wasm -o $d/pc.cwasm 2>/dev/null
  best() { local o=$1; shift; local m=999; for i in 1 2 3; do s=$(date +%s.%N); "$@" > $d/$o.txt 2>&1; e=$(date +%s.%N); t=$(echo "$e - $s" | bc); m=$(echo "if ($t < $m) $t else $m" | bc); done; printf "%.2f" $m; }
  p=$(best py python3 $f)
  j=$(cd $d && best jvm java -Xss64m Prog)
  w=$(cd $d && best wasm wasmtime run --allow-precompiled p.cwasm)
  c=$(cd $d && best comp wasmtime run --allow-precompiled pc.cwasm)
  g=--; v=--; if [ -n "$OTHERS" ]; then g=$(best go ~/.local/bin/starlark -recursion $f); v=$(best java ~/.local/bin/starlark-java $f); fi
  same=same; for o in jvm wasm comp ${OTHERS:+go java}; do cmp -s $d/py.txt $d/$o.txt || same="$same DIFFERS:$o"; done
  echo "$b: jvm $j  wasm $w  comp $c  cpython $p  go $g  java $v  size $(stat -c %s $d/Prog.class 2>/dev/null)/$(stat -c %s $d/p.wasm 2>/dev/null)/$(stat -c %s $d/pc.wasm 2>/dev/null)  $same"
done
