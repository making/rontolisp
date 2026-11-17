#!/bin/bash
JAR=$(git rev-parse --show-toplevel)/target/rontolisp-0.1.0-SNAPSHOT-exec.jar
n=$1; d=programs/out-$n; rm -rf $d; mkdir -p $d
java JavaSpike.java programs/$n.java > programs/$n.lisp
t() { local best=999; for i in 1 2 3; do s=$(python3 -c 'import time;print(time.time())'); "$@" > $d/last.txt 2>&1; e=$(python3 -c 'import time;print(time.time())'); best=$(python3 -c "print(min($best,$e-$s))"); done; printf "%.2f" $best; }
(cd $d && java -jar $JAR ../$n.lisp -o Prog.class 2>&1 | head -5)
java -jar $JAR programs/$n.lisp -o $d/p.wasm 2>&1 | head -5; wasmtime compile $d/p.wasm -o $d/p.cwasm
[ -f programs/$n.java ] && (mkdir -p $d/ref && javac -d $d/ref programs/$n.java && echo "$n javac: $(t java -cp $d/ref $n) s  -> $(head -c 60 $d/last.txt | tr '\n' ' ')")
echo "$n jvm:   $(t java -cp $d Prog) s  -> $(head -c 60 $d/last.txt | tr '\n' ' ')"
echo "$n wasm:  $(t wasmtime run --allow-precompiled $d/p.cwasm) s  -> $(head -c 60 $d/last.txt | tr '\n' ' ')"
ls -l $d/Prog.class $d/p.wasm | awk '{print $5, $9}'
