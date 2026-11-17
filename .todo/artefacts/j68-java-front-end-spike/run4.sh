#!/bin/bash
# run4.sh NAME : run programs/NAME.lisp on all four backends, diff against programs/NAME.expected
java JavaSpike.java programs/$1.java > programs/$1.lisp; java programs/$1.java > programs/$1.expected 2>&1
JAR=$(git rev-parse --show-toplevel)/target/rontolisp-0.1.0-SNAPSHOT-exec.jar
n=$1; d=programs/out-$n; rm -rf $d; mkdir -p $d
java -jar $JAR programs/$n.lisp > $d/interp.txt 2>&1
(cd $d && java -jar $JAR ../$n.lisp -o Prog.class && java Prog) > $d/jvm.txt 2>&1
(java -jar $JAR programs/$n.lisp -o $d/p.wasm && wasmtime run $d/p.wasm) > $d/wasm.txt 2>&1
(java -jar $JAR programs/$n.lisp -o $d/pc.wasm --component && wasmtime run $d/pc.wasm) > $d/comp.txt 2>&1
for b in interp jvm wasm comp; do
  if diff -q programs/$n.expected $d/$b.txt >/dev/null; then echo "$n $b: OK"; else echo "$n $b: DIFF"; diff programs/$n.expected $d/$b.txt | head -${2:-12}; fi
done
