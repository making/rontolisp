#!/usr/bin/env bash
# Regenerate the --wit template resources (component/wit/*.wit) that WitEmitter splices
# the rontolisp:wasm-export lines into. Requires wasm-tools AND a built rontolisp exec
# jar: unlike regen.sh, the templates cannot be captured from the uni*.wit reference
# components, because the fixed run / incoming-handler export (and its interface
# definition in the trailing package text) is wired by WasmComponentBuilder, not present
# in those references. So after changing the import surface the flow is two-phase:
#
#   1. edit the sources, run ./regen.sh, rebuild the jar (./mvnw package -DskipTests)
#   2. run ./regen-wit.sh, then re-run WitEmitterTest / WitOracleE2eTest
#
# Each template is `wasm-tools component wit` on a minimal reference component of that
# blob variant, with the reference program's own export lines stripped (the dynamic part
# WitEmitter re-inserts) and, on the serve variants only, the incoming-handler `use`
# clause restored: wasm-tools omits it and prints a WIT that does not parse (the upstream
# deps/http/handler.wit has the clause), and the whole point of --wit is a consumable
# file. WitOracleE2eTest pins both facts against the live tool.
set -euo pipefail
cd "$(dirname "$0")"
OUT=../main/resources/am/ik/rontolisp/codegen/wasm/component/wit
JAR=${JAR:-../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar}
[ -f "$JAR" ] || { echo "exec jar not found: $JAR (build with ./mvnw package -DskipTests, or set \$JAR)"; exit 1; }
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# capture <name> <extra flags...>  -- expects $WORK/<name>.lisp; writes $OUT/<name>.wit
capture() {
  local name=$1; shift
  java -jar "$JAR" "$WORK/$name.lisp" -o "$WORK/$name.wasm" --component "$@"
  # Strip the reference program's own export lines (keep the fixed wasi:* ones) and any
  # blank line left dangling directly before the world's closing brace.
  wasm-tools component wit "$WORK/$name.wasm" \
    | awk '/^  export / && !/^  export wasi:/ {next} {print}' \
    | awk 'NR > 1 { if (!(prev == "" && $0 == "}")) print prev } { prev = $0 } END { print prev }' \
    > "$OUT/$name.wit"
  echo "$OUT/$name.wit"
}

# restore_use <name>  -- re-add the use clause wasm-tools drops from incoming-handler
restore_use() {
  python3 - "$OUT/$1.wit" <<'PY'
import sys
path = sys.argv[1]
text = open(path).read()
old = "  interface incoming-handler {\n    handle: func(request: incoming-request, response-out: response-outparam);\n  }"
new = ("  interface incoming-handler {\n    use types.{incoming-request, response-outparam};\n\n"
       "    handle: func(request: incoming-request, response-out: response-outparam);\n  }")
assert text.count(old) == 1, path
open(path, "w").write(text.replace(old, new))
PY
  echo "$OUT/$1.wit (use clause restored)"
}

echo "== --wit templates =="
echo '(print "x")' > "$WORK/base.lisp"
capture base
echo '(print (rontolisp:fetch "http://127.0.0.1:9/"))' > "$WORK/http.lisp"
capture http
echo '(close (rontolisp:tcp-listen 7777))' > "$WORK/sock.lisp"
capture sock
printf '(defun h (r) (list :status 200 :body "x"))\n(rontolisp:http-handler (quote h))\n' > "$WORK/serve.lisp"
capture serve
restore_use serve
printf '(defun h (r) (list :status 200 :body (getf (rontolisp:fetch "http://127.0.0.1:9/") :body)))\n(rontolisp:http-handler (quote h))\n' > "$WORK/serve-http.lisp"
capture serve-http
restore_use serve-http
printf '(defun f (n) n)\n(rontolisp:wasm-export (quote f) :params (quote (:int)) :returns :int)\n' > "$WORK/nogc.lisp"
capture nogc --no-gc
printf '(defun hello () (print "hi"))\n(rontolisp:wasm-export (quote hello))\n' > "$WORK/nogc-print.lisp"
capture nogc-print --no-gc
echo "== done =="
