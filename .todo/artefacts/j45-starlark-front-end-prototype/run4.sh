#!/bin/bash
# usage: run4.sh prog.lisp  -> runs the program on the four backends, prints which differ
JAR=${JAR:?path of the rontolisp exec jar}
f=$1; b=$(basename $f .lisp); d=out-$b; mkdir -p $d
( java -jar $JAR $f > $d/int.txt 2>&1; echo "exit=$?" >> $d/int.txt ) &
( java -jar $JAR $f -o $d/Prog.class --class-name Prog > $d/jvm.log 2>&1 && (cd $d && java Prog > jvm.txt 2>&1; echo "exit=$?" >> jvm.txt) ) &
( java -jar $JAR $f -o $d/p.wasm > $d/wasm.log 2>&1 && (wasmtime run $d/p.wasm > $d/wasm.txt 2>&1; echo "exit=$?" >> $d/wasm.txt) ) &
( java -jar $JAR $f -o $d/pc.wasm --component > $d/comp.log 2>&1 && (wasmtime run $d/pc.wasm > $d/comp.txt 2>&1; echo "exit=$?" >> $d/comp.txt) ) &
wait
for x in jvm wasm comp; do
  if [ ! -f $d/$x.txt ]; then echo "$b $x: COMPILE FAILED"; tail -3 $d/$x.log;
  elif cmp -s $d/int.txt $d/$x.txt; then echo "$b $x: same"; else echo "$b $x: DIFF"; diff $d/int.txt $d/$x.txt | head -6; fi
done
