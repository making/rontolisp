#!/bin/bash
# four.sh JAR File.java [stdin-file]: the program under the JDK, the interpreter, the JVM,
# wasm and the component; each leg's lines against the JDK's
J=$1; f=$(realpath "$2"); in=${3:-/dev/null}; b=$(basename "$f" .java); o=$(mktemp -d)
cp "$f" "$o/"; cd "$o"
java -Duser.language=en -Duser.country=US "$b.java" < "$in" > jdk.txt 2>&1
java -jar "$J" "$b.java" < "$in" > interpreter.txt 2>&1
if java -jar "$J" "$b.java" -o "P$b.class" --class-name "P$b" > jvmc.txt 2>&1; then java -cp . "P$b" < "$in" > jvm.txt 2>&1; else cp jvmc.txt jvm.txt; fi
if java -jar "$J" "$b.java" -o "$b.wasm" > wasmc.txt 2>&1; then wasmtime run "$b.wasm" < "$in" > wasm.txt 2>&1; else cp wasmc.txt wasm.txt; fi
if java -jar "$J" "$b.java" -o "$b-c.wasm" --component > compc.txt 2>&1; then wasmtime run "$b-c.wasm" < "$in" > component.txt 2>&1; else cp compc.txt component.txt; fi
for leg in interpreter jvm wasm component; do
  echo "$b $leg: $(wc -l < $leg.txt) lines, $(diff jdk.txt $leg.txt | grep -c '^<') differ from the JDK's $(wc -l < jdk.txt)"
done
echo "$o"
