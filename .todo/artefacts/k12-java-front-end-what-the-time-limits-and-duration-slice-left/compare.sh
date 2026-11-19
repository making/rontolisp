#!/bin/bash
# compare.sh File.java [all]
# Runs a probe under the JDK and under rontolisp (the interpreter; with "all" also the
# JVM, wasm and the component) and diffs each against the JDK's output. JAR is the exec
# jar; the work files go under TMPDIR.
JAR=$(realpath "${JAR:-target/rontolisp-0.1.0-SNAPSHOT-exec.jar}")
f=$(realpath "$1"); b=$(basename "$f" .java); o=$(mktemp -d)
java "$f" > "$o/jdk.txt" 2>&1
java -jar "$JAR" "$f" > "$o/interpreter.txt" 2>&1
diff "$o/jdk.txt" "$o/interpreter.txt" > "$o/interpreter.diff" && echo "interpreter: same ($(wc -l < "$o/jdk.txt") lines)" || { echo "interpreter: differs"; head -20 "$o/interpreter.diff"; }
[ "$2" = all ] || exit 0
(cd "$o" && java -jar "$JAR" "$f" -o "P$b.class" --class-name "P$b" && java -cp . "P$b" > jvm.txt 2>&1; diff jdk.txt jvm.txt > jvm.diff && echo "jvm: same" || { echo "jvm: differs"; head -20 jvm.diff; })
(cd "$o" && java -jar "$JAR" "$f" -o "$b.wasm" && wasmtime run "$b.wasm" > wasm.txt 2>&1; diff jdk.txt wasm.txt > wasm.diff && echo "wasm: same ($(stat -c %s "$b.wasm") B)" || { echo "wasm: differs"; head -20 wasm.diff; })
(cd "$o" && java -jar "$JAR" "$f" -o "$b-c.wasm" --component && wasmtime run "$b-c.wasm" > component.txt 2>&1; diff jdk.txt component.txt > component.diff && echo "component: same" || { echo "component: differs"; head -20 component.diff; })
