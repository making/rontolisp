#!/usr/bin/env bash
# Runs the hand-lowered probes on all four backends. From the repository root, after
# `./mvnw clean spring-javaformat:apply package -DskipTests`:
#
#   .todo/artefacts/825-minimal-experimental-scheme-front-end/run.sh
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
jar=$(cd "$here/../../.." && pwd)/target/rontolisp-0.1.0-SNAPSHOT-exec.jar
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cd "$work"

legs() { # <source> <ClassName>
	echo -n "interpreter: "; java -jar "$jar" "$1" 2>&1 | tr '\n' ' ' | cut -c1-100; echo
	java -jar "$jar" "$1" -o "$2.class" > /dev/null 2>&1
	echo -n "jvm:         "; java "$2" 2>&1 | tr '\n' ' ' | cut -c1-100; echo
	java -jar "$jar" "$1" -o "$2.wasm" > /dev/null 2>&1
	echo -n "wasm:        "; wasmtime run "$2.wasm" 2>&1 | tr '\n' ' ' | cut -c1-100; echo
	java -jar "$jar" "$1" -o "$2-comp.wasm" --component > /dev/null 2>&1
	echo -n "component:   "; wasmtime run "$2-comp.wasm" 2>&1 | tr '\n' ' ' | cut -c1-100; echo
}

echo "== lowered-shape.lisp"
cp "$here/lowered-shape.lisp" Lowered.lisp
legs Lowered.lisp Lowered

for n in 10000 100000 1000000; do
	echo "== mutual-tail N=$n"
	sed "s/@N@/$n/" "$here/mutual-tail.lisp.in" > Mutual.lisp
	legs Mutual.lisp Mutual
done

for d in defvar setq; do
	echo "== global-define via $d (Scheme answers 1)"
	sed "s/@DEF@/$d/" "$here/global-define.lisp.in" > Global.lisp
	legs Global.lisp Global
done

echo "== uppercase-identifier.lisp (interpreter)"
java -jar "$jar" "$here/uppercase-identifier.lisp"

echo "== printer-leaves.lisp (interpreter)"
java -jar "$jar" "$here/printer-leaves.lisp"
