#!/bin/bash
# four.sh JAR File.java: the program on the interpreter, the JVM, wasm and the component,
# each diffed against the interpreter's output; the JDK's output is File.jdk.txt beside it
J=$1; f=$(realpath "$2"); b=$(basename "$f" .java); d=$(dirname "$f"); o=$(mktemp -d)
cp "$f" "$o/"; cd "$o"
java -jar "$J" "$b.java" > interpreter.txt 2>&1
java -jar "$J" "$b.java" -o "P$b.class" --class-name "P$b" > /dev/null 2>&1 && java -cp . "P$b" > jvm.txt 2>&1
java -jar "$J" "$b.java" -o "$b.wasm" > /dev/null 2>&1 && wasmtime run "$b.wasm" > wasm.txt 2>&1
java -jar "$J" "$b.java" -o "$b-c.wasm" --component > /dev/null 2>&1 && wasmtime run "$b-c.wasm" > component.txt 2>&1
for leg in interpreter jvm wasm component; do
  if [ -f "$d/$b.jdk.txt" ]; then n=$(diff "$d/$b.jdk.txt" $leg.txt | grep -c '^<'); else n=-; fi
  echo "$b $leg: $(wc -l < $leg.txt) lines, $n lines differ from the JDK, $(diff interpreter.txt $leg.txt | grep -c '^<') from the interpreter"
done
cp interpreter.txt "$d/$b.int.txt"; cp wasm.txt "$d/$b.wasm.txt"; cp jvm.txt "$d/$b.jvm.txt"; cp component.txt "$d/$b.comp.txt"
