#!/bin/bash
# run.sh: each shape of Stages.java on the JVM class, the precompiled wasm and a JDK,
# the best of three, ns an element. JAR is the exec jar; run in a scratch directory.
JAR=$(realpath "${JAR:-target/rontolisp-0.1.0-SNAPSHOT-exec.jar}")
here=$(dirname "$(realpath "$0")"); work=$(mktemp -d); cd "$work"
java -jar "$JAR" "$here/Stages.java" -o Stages.wasm && java -jar "$JAR" "$here/Stages.java" -o PStages.class --class-name PStages && wasmtime compile Stages.wasm -o Stages.cwasm
best() { for i in 1 2 3; do "$@"; done | awk '{print $(NF-3)}' | sort -n | head -1; }
for w in ${SHAPES:-loop arraySum arrayMap arrayMapFilter arrayMap3 rangeIntSum rangeSum listLoop listSum listMap listForEach listCollect}; do
  echo "$w: jvm $(WHICH=$w best java -cp . PStages) wasm $(best wasmtime run --allow-precompiled --env WHICH=$w Stages.cwasm) jdk $(WHICH=$w best java "$here/Stages.java") ns"
done
