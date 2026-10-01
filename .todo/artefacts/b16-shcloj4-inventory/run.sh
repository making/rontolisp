#!/usr/bin/env bash
# Re-run every probe under the oracle and rontolisp and diff against expected/.
# Usage: run.sh [rontolisp-exec-jar]
# Defaults to the in-worktree build (./mvnw -DskipTests package).
# Volatile oracle outputs (timestamps, temp paths, object hashes, directory
# counts) are matched by pattern; everything else byte-exact.
set -u
cd "$(dirname "$0")/probes"
JAR="$1"
if [ -z "${JAR:-}" ]; then
  JAR="$(ls ../../target/rontolisp-*-exec.jar 2>/dev/null | head -1)"
fi
[ -n "${JAR:-}" ] && [ -f "$JAR" ] || { echo "missing exec jar (build one first)"; exit 2; }
JAR="$(realpath "$JAR")"

# probe-base -> required oracle substring (instead of exact diff)
declare -A PATTERNS=(
  [b20-zeroarg-static]='^[0-9]+$'
  [ng-fileseq]='^[0-9]+$'
  [ng-allns]='^[0-9]+$'
  [diverge-pi-binding]="Can't dynamically bind non-dynamic var"
  [rq-unquoted]='ClassNotFoundException'
  [ng-proxysuper]='Unable to resolve symbol'
  [ng-eduction]='clojure.core.Eduction'
)

pass=0; fail=0; failed=()
for probe in *.clj; do
  base="$(basename "$probe" .clj)"
  oracle_out="$(timeout 60 clj -M "$probe" 2>&1)"
  ronto_out="$(java -jar "$JAR" --source-language clojure "$probe" 2>&1)"
  ok=1
  if [[ -v "PATTERNS[$base]" ]]; then
    echo "$oracle_out" | grep -Eq "${PATTERNS[$base]}" || {
      echo "FAIL $base (oracle pattern)"; echo "$oracle_out" | head -3; ok=0; }
  else
    diff <(printf '%s\n' "$oracle_out") "../expected/${base}.oracle.txt" > /dev/null || {
      echo "FAIL $base (oracle diff)"; ok=0; }
  fi
  diff <(printf '%s\n' "$ronto_out") "../expected/${base}.ronto.txt" > /dev/null || {
    echo "FAIL $base (ronto diff)"; echo "$ronto_out" | head -3; ok=0; }
  if [ "$ok" = 1 ]; then pass=$((pass+1)); else fail=$((fail+1)); failed+=("$base"); fi
done
echo "pass=$pass fail=$fail total=$((pass+fail))"
[ "$fail" = 0 ] || { echo "failed: ${failed[*]}"; exit 1; }
