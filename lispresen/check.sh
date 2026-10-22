#!/usr/bin/env bash
# Verifies lispresen: renders examples/intro.lisp on the interpreter, the JVM, WASM, the
# WASM component, a native executable and SBCL, requires each output to equal
# examples/intro.html byte for byte and the deck to raise no diagnostic, then runs the
# unit tests on the interpreter. SBCL is the reference Common Lisp: lispresen is written
# in portable Common Lisp, with rontolisp-only code behind #+rontolisp.
#
#   lispresen/check.sh            # verify
#   lispresen/check.sh --update   # rewrite the golden HTML from the interpreter first
#
# Needs the executable jar (./mvnw package; RONTOLISP_JAR overrides its path), java and
# wasmtime; the SBCL leg is skipped when sbcl is not on PATH. The unit tests stay on
# rontolisp: rove on SBCL needs bordeaux-threads, which is not vendored here.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)
jar=${RONTOLISP_JAR:-$root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar}
deck=$here/examples/intro.lisp
golden=$here/examples/intro.html
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

if [[ ${1:-} == --update ]]; then
  java -jar "$jar" "$here/cli.lisp" -- - -o - < "$deck" > "$golden"
fi

status=0
check() {
  local name=$1
  shift
  if "$@" - -o - < "$deck" > "$work/$name.html" 2> "$work/$name.err" \
      && [[ ! -s $work/$name.err ]] && cmp -s "$golden" "$work/$name.html"; then
    echo "ok   $name"
  else
    echo "FAIL $name"
    cat "$work/$name.err"
    status=1
  fi
}

check interpreter java -jar "$jar" "$here/cli.lisp" --
java -jar "$jar" "$here/cli.lisp" -o "$work/lispresen.jar" > /dev/null
check jvm java -jar "$work/lispresen.jar"
java -jar "$jar" "$here/cli.lisp" -o "$work/lispresen.wasm" > /dev/null
check wasm wasmtime run "$work/lispresen.wasm"
java -jar "$jar" "$here/cli.lisp" --component -o "$work/lispresen-component.wasm" > /dev/null
check wasm-component wasmtime run "$work/lispresen-component.wasm"
# A jar built without the precompiler for this platform cannot make an executable.
if java -jar "$jar" "$here/cli.lisp" --native -o "$work/lispresen" > "$work/native.log" 2>&1; then
  check native "$work/lispresen"
else
  echo "skip native: $(head -1 "$work/native.log")"
fi
if command -v sbcl > /dev/null; then
  check sbcl sbcl --script "$here/cli.lisp"
else
  echo "skip sbcl: not on PATH"
fi

resources=$root/src/test/resources
if java -jar "$jar" "$here/test/lispresen-test.lisp" \
    --system-path "$resources/rove:$resources/dissect:$resources/cl-ppcre" > "$work/test.log" 2>&1; then
  echo "ok   unit tests"
else
  cat "$work/test.log"
  echo "FAIL unit tests"
  status=1
fi
exit $status
