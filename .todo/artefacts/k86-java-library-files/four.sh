#!/bin/bash
# four.sh JAR File.(java|lisp) [wasm-dir-mode]: the program under the JDK (a .java),
# the interpreter, the JVM, wasm and the component, each leg in a directory of its own
# holding locked.txt (mode 000) and ro/ (mode 555); a wasm leg preopens its directory
# as "." (or, with "abs" as the third argument, under its absolute name). Each leg's
# lines against the first leg's.
J=$(realpath "$1"); f=$(realpath "$2"); mode=${3:-dot}
ext=${f##*.}; b=$(basename "$f" ".$ext"); o=$(mktemp -d); trap 'chmod -R u+rwx "$o"' EXIT
leg() {
  local d="$o/$1"; mkdir -p "$d"; cp "$f" "$d/"; printf 'secret' > "$d/locked.txt"; chmod 000 "$d/locked.txt"
  mkdir "$d/ro"; chmod 555 "$d/ro"; echo "$d"
}
legs=""
if [ "$ext" = java ]; then
  d=$(leg jdk); (cd "$d" && java -Duser.language=en -Duser.country=US "$b.java" > ../jdk.txt 2>&1); legs="jdk"
fi
d=$(leg interpreter); (cd "$d" && java -jar "$J" "$b.$ext" > ../interpreter.txt 2>&1)
d=$(leg jvm); (cd "$d" && if java -jar "$J" "$b.$ext" -o "$o/P$b.class" --class-name "P$b" > ../jvmc.txt 2>&1; then java -cp "$o" "P$b" > ../jvm.txt 2>&1; else cp ../jvmc.txt ../jvm.txt; fi)
d=$(leg wasm); (cd "$d" && if java -jar "$J" "$b.$ext" -o "$o/$b.wasm" > ../wasmc.txt 2>&1; then
  if [ "$mode" = abs ]; then wasmtime run -W gc=y -W exceptions=y --dir "$d::$d" "$o/$b.wasm" > ../wasm.txt 2>&1; else wasmtime run -W gc=y -W exceptions=y --dir . "$o/$b.wasm" > ../wasm.txt 2>&1; fi
  else cp ../wasmc.txt ../wasm.txt; fi)
d=$(leg component); (cd "$d" && if java -jar "$J" "$b.$ext" -o "$o/$b-c.wasm" --component > ../compc.txt 2>&1; then
  if [ "$mode" = abs ]; then wasmtime run -W gc=y -W exceptions=y --dir "$d::$d" "$o/$b-c.wasm" > ../component.txt 2>&1; else wasmtime run -W gc=y -W exceptions=y --dir . "$o/$b-c.wasm" > ../component.txt 2>&1; fi
  else cp ../compc.txt ../component.txt; fi)
first=${legs:-interpreter}
for l in jdk interpreter jvm wasm component; do
  [ -f "$o/$l.txt" ] || continue
  echo "$b $l: $(wc -l < "$o/$l.txt") lines, $(diff "$o/$first.txt" "$o/$l.txt" | grep -c '^<') of $first's differ"
done
echo "$o"
