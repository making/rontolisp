#!/bin/bash
# Compiles one program with two rontolisp jars and compares every class file byte for byte --
# the check a slice moved onto MethodCode must pass (a84: identical but for the jar build
# timestamp uiop/os:lisp-version-string embeds; MethodDiff.java names what differs).
#
#   cmpjars.sh <jar-before> <jar-after> <work-dir> <program.lisp> [compile args...]
a="$1"; b="$2"; root="$3"; src="$4"; shift 4
name=$(basename "$src" .lisp)
da="$root/$name-a"; db="$root/$name-b"
rm -rf "$da" "$db"; mkdir -p "$da" "$db"
cp "$src" "$da/"; cp "$src" "$db/"
(cd "$da" && java -jar "$a" "$(basename "$src")" -o P.class "$@" > out.txt 2>&1); sa=$?
(cd "$db" && java -jar "$b" "$(basename "$src")" -o P.class "$@" > out.txt 2>&1); sb=$?
if [ $sa -ne 0 ] || [ $sb -ne 0 ]; then echo "$name: compile exit a=$sa b=$sb"; exit 1; fi
diffs=0; count=0
while IFS= read -r f; do
  count=$((count+1))
  if ! cmp -s "$da/$f" "$db/$f"; then echo "$name: DIFFERS $f"; diffs=$((diffs+1)); fi
done < <(cd "$da" && find . -name '*.class' | sort)
echo "$name: $count class files, $diffs differ"
