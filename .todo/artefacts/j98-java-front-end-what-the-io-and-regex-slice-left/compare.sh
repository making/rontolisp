#!/bin/bash
# compare.sh File.java [all]
# Runs a probe under the JDK and under rontolisp (the interpreter; with "all" also the
# JVM, wasm and the component) and diffs each against the JDK's output. A file named as
# the probe with .stdin is its standard input. JAR is the exec jar.
JAR=${JAR:-target/rontolisp-0.1.0-SNAPSHOT-exec.jar}
f=$(realpath "$1"); b=$(basename "$f" .java); o=$(mktemp -d)
in=/dev/null; [ -f "${f%.java}.stdin" ] && in="${f%.java}.stdin"
java "$f" < "$in" > "$o/jdk.txt" 2>&1
java -jar "$JAR" "$f" < "$in" > "$o/interpreter.txt" 2>&1
diff "$o/jdk.txt" "$o/interpreter.txt" > "$o/interpreter.diff" && echo "interpreter: same ($(wc -l < "$o/jdk.txt") lines)" || { echo "interpreter: differs"; head -20 "$o/interpreter.diff"; }
[ "$2" = all ] || exit 0
(cd "$o" && java -jar "$OLDPWD/$JAR" "$f" -o "P$b.class" --class-name "P$b" && java -cp . "P$b" < "$in" > jvm.txt 2>&1; diff jdk.txt jvm.txt > jvm.diff && echo "jvm: same" || { echo "jvm: differs"; head -20 jvm.diff; })
(cd "$o" && java -jar "$OLDPWD/$JAR" "$f" -o "$b.wasm" && wasmtime run "$b.wasm" < "$in" > wasm.txt 2>&1; diff jdk.txt wasm.txt > wasm.diff && echo "wasm: same ($(stat -c %s "$b.wasm") B)" || { echo "wasm: differs"; head -20 wasm.diff; })
(cd "$o" && java -jar "$OLDPWD/$JAR" "$f" -o "$b-c.wasm" --component && wasmtime run "$b-c.wasm" < "$in" > component.txt 2>&1; diff jdk.txt component.txt > component.diff && echo "component: same" || { echo "component: differs"; head -20 component.diff; })
