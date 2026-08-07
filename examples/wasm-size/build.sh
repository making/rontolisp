#!/usr/bin/env bash
# Build every rontolisp Wasm variant of hello_world / pi_approx, run each one,
# check its output, and print the size table that README.md quotes.
#
# Usage:
#   ./build.sh            # build + validate + report
#   ./build.sh clean      # remove out/
#
# The compiler is picked in this order:
#   $RONTOLISP            # an explicit path, if you set one
#   target/rontolisp      # the GraalVM native binary (fastest)
#   target/...-exec.jar   # the executable jar, run with `java -jar`
#
# wasmtime is optional: without it the modules are still built and measured,
# only the run/output checks are skipped.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../.." && pwd)"
out="$here/out"

if [[ "${1:-}" == "clean" ]]; then
  rm -rf "$out"
  echo "removed $out"
  exit 0
fi

# --- the compiler -----------------------------------------------------------
if [[ -n "${RONTOLISP:-}" ]]; then
  ronto=("$RONTOLISP")
elif [[ -x "$repo_root/target/rontolisp" ]]; then
  ronto=("$repo_root/target/rontolisp")
else
  jar="$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
  if [[ ! -f "$jar" ]]; then
    echo "No rontolisp binary or jar found." >&2
    echo "Build one from the repo root:" >&2
    echo "  ./mvnw clean package -DskipTests                 # the jar" >&2
    echo "  ./mvnw -Pnative clean package -DskipTests        # the native binary" >&2
    exit 1
  fi
  ronto=(java -jar "$jar")
fi
echo "compiler: ${ronto[*]}"

if command -v wasmtime >/dev/null 2>&1; then
  have_wasmtime=1
else
  have_wasmtime=0
  echo "NOTE: wasmtime not on PATH -- building and measuring only, no run checks."
fi

mkdir -p "$out"

# --- what to build ----------------------------------------------------------
# One row per artifact: NAME|SOURCE|COMPILE FLAGS|RUN ARGS|EXPECTED FIRST LINE
#
# The RUN ARGS column is the wasmtime invocation minus the module path. The
# --no-gc rows are reactors (no `_start`), so the host names the export.
hello_expected='Hello, World!'
pi_expected='pi = 3.141591653589774'
pi_nogc_expected='3.141591'

builds=(
  "hello_world_plain|hello_world/hello_world.lisp||-W gc|$hello_expected"
  "hello_world_optimize|hello_world/hello_world.lisp|--optimize|-W gc|$hello_expected"
  "hello_world_size|hello_world/hello_world.lisp|--optimize=size|-W gc|$hello_expected"
  "hello_world_component|hello_world/hello_world.lisp|--component --optimize=size|-W gc=y|$hello_expected"
  "hello_world_nogc|hello_world/hello_world-nogc.lisp|--no-gc --optimize=size|--invoke say-hello|$hello_expected"
  "pi_approx_plain|pi_approx/pi_approx.lisp||-W gc|$pi_expected"
  "pi_approx_optimize|pi_approx/pi_approx.lisp|--optimize|-W gc|$pi_expected"
  "pi_approx_size|pi_approx/pi_approx.lisp|--optimize=size|-W gc|$pi_expected"
  "pi_approx_component|pi_approx/pi_approx.lisp|--component --optimize=size|-W gc=y|$pi_expected"
  "pi_approx_nogc|pi_approx/pi_approx-nogc.lisp|--no-gc --optimize=size|--invoke approx-pi|$pi_nogc_expected"
)

# --- build ------------------------------------------------------------------
echo ""
echo "=== Build ==="
for row in "${builds[@]}"; do
  IFS='|' read -r name src flags _runargs _expected <<<"$row"
  printf '%-24s %s\n' "$name" "${flags:-(no flags)}"
  # shellcheck disable=SC2086 -- flags is a deliberate word list
  "${ronto[@]}" "$here/$src" -o "$out/$name.wasm" $flags
done

# --- validate ---------------------------------------------------------------
fail=0
echo ""
echo "=== Validation (wasmtime) ==="
for row in "${builds[@]}"; do
  IFS='|' read -r name _src _flags runargs expected <<<"$row"
  printf '%-24s ' "$name:"
  if [[ "$have_wasmtime" != 1 ]]; then
    echo "SKIP (no wasmtime)"
    continue
  fi
  # shellcheck disable=SC2086 -- runargs is a deliberate word list
  actual="$(wasmtime run $runargs "$out/$name.wasm" 2>/dev/null | head -1 || true)"
  if [[ "$actual" == "$expected" ]]; then
    echo "OK ($actual)"
  else
    echo "FAIL (expected '$expected', got '$actual')"
    fail=1
  fi
done

if [[ "$fail" -ne 0 ]]; then
  echo ""
  echo "ERROR: some modules produced the wrong output" >&2
  exit 1
fi

# --- report -----------------------------------------------------------------
echo ""
echo "=== Wasm Size Report ==="
echo ""
printf '| %-24s | %-28s | %12s |\n' "Artifact" "Flags" "Size (bytes)"
printf '| %-24s | %-28s | %12s |\n' "------------------------" "----------------------------" "-----------:"
for row in "${builds[@]}"; do
  IFS='|' read -r name _src flags _runargs _expected <<<"$row"
  size="$(wc -c <"$out/$name.wasm" | tr -d ' ')"
  # Group the digits the way the README table does.
  pretty="$(printf '%s' "$size" | rev | sed 's/.\{3\}/&,/g' | rev | sed 's/^,//')"
  printf '| %-24s | %-28s | %12s |\n' "$name" "${flags:-(none)}" "$pretty"
done
echo ""
