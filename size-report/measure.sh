#!/usr/bin/env bash
# Measure every artifact the size report tracks and regenerate results/.
#
# Usage:
#   ./measure.sh            # both families, rewrite results/
#   ./measure.sh wasm       # the flag matrix over programs/ only
#   ./measure.sh workers    # the Cloudflare Worker modules only
#   ./measure.sh clean      # remove out/
#
# The compiler is picked in this order:
#   $RONTOLISP            # an explicit path, if you set one
#   target/rontolisp      # the GraalVM native binary (fastest)
#   target/...-exec.jar   # the executable jar, run with `java -jar`
#
# wasmtime is optional; without it the `wasm` family is built and measured but
# not run. `npx` is optional; without it the jco-transpiled component row is
# skipped. The worker builds `ql:quickload` clack/lack/tiny-routes/ningle into
# ~/.rontolisp/quicklisp on first run, so the first invocation needs network.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/.." && pwd)"
out="$here/out"
results="$here/results"
# The prose half of each report: what the family measures and how to read it.
# Kept as plain markdown here and appended verbatim below the generated table,
# so a regenerated results/ file still explains itself.
notes="$here/notes"

what="${1:-all}"
if [[ "$what" == "clean" ]]; then
  rm -rf "$out"
  echo "removed $out"
  exit 0
fi
case "$what" in
  all | wasm | workers) ;;
  *)
    echo "usage: $0 [all|wasm|workers|clean]" >&2
    exit 2
    ;;
esac

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

have_wasmtime=0
command -v wasmtime >/dev/null 2>&1 && have_wasmtime=1
[[ "$have_wasmtime" == 1 ]] || echo "NOTE: wasmtime not on PATH -- measuring only, no run checks."

# `rontolisp -v` answers a JSON object; the report only wants the version string.
version="$("${ronto[@]}" -v 2>/dev/null | sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' | head -1)"
version="${version:-unknown}"
commit="$(git -C "$repo_root" rev-parse --short HEAD 2>/dev/null || echo unknown)"
today="$(date -u +%Y-%m-%d)"

mkdir -p "$out" "$results"

# --- helpers ----------------------------------------------------------------
# Group digits the way the tables do: 124307 -> 124,307
commas() { printf '%s' "$1" | rev | sed 's/.\{3\}/&,/g' | rev | sed 's/^,//'; }
# A flag list as a table cell: `--optimize`, or (none) when there are no flags.
flag_cell() {
  if [[ -n "$1" ]]; then printf '`%s`' "$1"; else printf '(none)'; fi
}
bytes_of() { wc -c <"$1" | tr -d ' '; }
# -n so the timestamp never lands in the compressed size.
gzip_of() { gzip -9 -n -c "$1" | wc -c | tr -d ' '; }

json_rows=()

# ============================================================================
# Family 1: the flag matrix over programs/
# ============================================================================
# NAME|SOURCE|COMPILE FLAGS|RUN ARGS|EXPECTED FIRST LINE
hello_expected='Hello, World!'
pi_expected='pi = 3.141591653589774'
pi_nogc_expected='3.141591'

wasm_builds=(
  "hello_world_plain|programs/hello_world/hello_world.lisp||-W gc|$hello_expected"
  "hello_world_optimize|programs/hello_world/hello_world.lisp|--optimize|-W gc|$hello_expected"
  "hello_world_size|programs/hello_world/hello_world.lisp|--optimize=size|-W gc|$hello_expected"
  "hello_world_component|programs/hello_world/hello_world.lisp|--component --optimize=size|-W gc=y|$hello_expected"
  "hello_world_nogc|programs/hello_world/hello_world-nogc.lisp|--no-gc --optimize=size|--invoke say-hello|$hello_expected"
  "pi_approx_plain|programs/pi_approx/pi_approx.lisp||-W gc|$pi_expected"
  "pi_approx_optimize|programs/pi_approx/pi_approx.lisp|--optimize|-W gc|$pi_expected"
  "pi_approx_size|programs/pi_approx/pi_approx.lisp|--optimize=size|-W gc|$pi_expected"
  "pi_approx_component|programs/pi_approx/pi_approx.lisp|--component --optimize=size|-W gc=y|$pi_expected"
  "pi_approx_nogc|programs/pi_approx/pi_approx-nogc.lisp|--no-gc --optimize=size|--invoke approx-pi|$pi_nogc_expected"
)

measure_wasm_family() {
  echo ""
  echo "=== Build: programs/ ==="
  for row in "${wasm_builds[@]}"; do
    IFS='|' read -r name src flags _runargs _expected <<<"$row"
    printf '%-24s %s\n' "$name" "${flags:-(no flags)}"
    # shellcheck disable=SC2086 -- flags is a deliberate word list
    "${ronto[@]}" "$here/$src" -o "$out/$name.wasm" $flags
  done

  echo ""
  echo "=== Validate: programs/ ==="
  local fail=0
  for row in "${wasm_builds[@]}"; do
    IFS='|' read -r name _src _flags runargs expected <<<"$row"
    printf '%-24s ' "$name:"
    if [[ "$have_wasmtime" != 1 ]]; then
      echo "SKIP (no wasmtime)"
      continue
    fi
    # shellcheck disable=SC2086 -- runargs is a deliberate word list
    local actual
    actual="$(wasmtime run $runargs "$out/$name.wasm" 2>/dev/null | head -1 || true)"
    if [[ "$actual" == "$expected" ]]; then
      echo "OK ($actual)"
    else
      echo "FAIL (expected '$expected', got '$actual')"
      fail=1
    fi
  done
  [[ "$fail" -eq 0 ]] || {
    echo "ERROR: some modules produced the wrong output" >&2
    exit 1
  }

  # --- results/wasm-flags.md ---
  local f="$results/wasm-flags.md"
  {
    echo "# Wasm flag matrix"
    echo ""
    echo "Generated by \`size-report/measure.sh\` -- do not edit by hand;"
    echo "the prose below it is [\`../notes/wasm-flags.md\`](../notes/wasm-flags.md)."
    echo "How the report is built and run: [../README.md](../README.md)."
    echo ""
    echo "- measured: $today"
    echo "- rontolisp: $version (\`$commit\`)"
    echo "- validated on: ${wasmtime_version:-not run}"
    echo ""
    echo "| Program | Flags | Size (bytes) |"
    echo "| --- | --- | ---: |"
    for row in "${wasm_builds[@]}"; do
      IFS='|' read -r name src flags _runargs _expected <<<"$row"
      local size prog
      size="$(bytes_of "$out/$name.wasm")"
      prog="$(basename "$(dirname "$src")")"
      [[ "$src" == *-nogc.lisp ]] && prog="$prog (nogc source)"
      printf '| %s | %s | %s |\n' "$prog" "$(flag_cell "$flags")" "$(commas "$size")"
      json_rows+=("{\"family\":\"wasm\",\"name\":\"$name\",\"flags\":\"${flags:-}\",\"bytes\":$size}")
    done
    echo ""
    cat "$notes/wasm-flags.md"
  } >"$f"
  echo "wrote $f"
}

# ============================================================================
# Family 2: the Cloudflare Worker modules
# ============================================================================
# NAME|SOURCE (relative to repo root)|COMPILE FLAGS|SED EXPR (empty = build the
# source as-is; otherwise the source is copied and rewritten first)
worker_builds=(
  "hello|examples/cloudflare-workers/hello/worker.lisp|--no-gc --optimize|"
  "hello-clack|examples/cloudflare-workers/hello-clack/worker.lisp|--no-wasi --optimize=size|"
  "hello-tiny-routes|examples/cloudflare-workers/hello-tiny-routes/worker.lisp|--no-wasi --optimize=size|"
  "hello-tiny-routes (full tiny-routes)|examples/cloudflare-workers/hello-tiny-routes/worker.lisp|--no-wasi --optimize=size|s|tiny-routes/lite|tiny-routes|g"
  "hello-ningle|examples/cloudflare-workers/hello-ningle/worker.lisp|--no-wasi --optimize=size|"
  "httpbin|examples/cloudflare-workers/httpbin/worker.lisp|--no-wasi --optimize=size|"
  "httpbin-clack|examples/net/httpbin-clack.lisp|--no-wasi --optimize=size|"
  "httpbin-tiny-routes|examples/cloudflare-workers/httpbin-tiny-routes/worker.lisp|--no-wasi --optimize=size|"
  "httpbin-tiny-routes (full tiny-routes)|examples/cloudflare-workers/httpbin-tiny-routes/worker.lisp|--no-wasi --optimize=size|s|tiny-routes/lite|tiny-routes|g"
  "httpbin-component (core module)|examples/cloudflare-workers/httpbin/worker.lisp|--component --no-wasi --optimize=size|"
)

measure_workers_family() {
  echo ""
  echo "=== Build: Cloudflare Worker modules ==="
  local -a names raws gzips flagsv slugs
  local i=0
  for row in "${worker_builds[@]}"; do
    local name src flags sedexpr
    name="${row%%|*}"
    local rest="${row#*|}"
    src="${rest%%|*}"
    rest="${rest#*|}"
    flags="${rest%%|*}"
    sedexpr="${rest#*|}"

    local slug build_src
    slug="w$i"
    build_src="$repo_root/$src"
    if [[ -n "$sedexpr" ]]; then
      build_src="$out/$slug.lisp"
      sed "$sedexpr" "$repo_root/$src" >"$build_src"
    fi

    printf '%-40s %s\n' "$name" "$flags"
    # shellcheck disable=SC2086 -- flags is a deliberate word list
    "${ronto[@]}" "$build_src" -o "$out/$slug.wasm" $flags

    names[i]="$name"
    slugs[i]="$slug"
    flagsv[i]="$flags"
    raws[i]="$(bytes_of "$out/$slug.wasm")"
    gzips[i]="$(gzip_of "$out/$slug.wasm")"
    json_rows+=("{\"family\":\"workers\",\"name\":\"$name\",\"flags\":\"$flags\",\"bytes\":${raws[i]},\"gzip\":${gzips[i]}}")
    i=$((i + 1))
  done

  # The jco-transpiled glue, when npx is available: the component row's real
  # cost on a Worker is the core module PLUS the generated JavaScript.
  local jco_js="" component_slug="${slugs[$((${#slugs[@]} - 1))]}"
  if command -v npx >/dev/null 2>&1; then
    echo "transpiling the component with jco"
    # The same flags examples/cloudflare-workers/httpbin-component/build.sh uses.
    if npx -y @bytecodealliance/jco transpile "$out/$component_slug.wasm" \
      -o "$out/jco" --instantiation sync -b 0 --bindgen-enable-wasm-exnref >/dev/null 2>&1; then
      jco_js="$(cat "$out"/jco/*.js | wc -c | tr -d ' ')"
      json_rows+=("{\"family\":\"workers\",\"name\":\"httpbin-component (jco glue, .js)\",\"flags\":\"jco transpile\",\"bytes\":$jco_js}")
    else
      echo "NOTE: jco transpile failed -- skipping the generated-glue row."
    fi
  else
    echo "NOTE: npx not on PATH -- skipping the generated-glue row."
  fi

  local f="$results/cloudflare-workers.md"
  {
    echo "# Cloudflare Worker module sizes"
    echo ""
    echo "Generated by \`size-report/measure.sh\` -- do not edit by hand;"
    echo "the prose below it is [\`../notes/cloudflare-workers.md\`](../notes/cloudflare-workers.md)."
    echo "What each Worker is: [examples/cloudflare-workers/](../../examples/cloudflare-workers/)."
    echo "How the report is built and run: [../README.md](../README.md)."
    echo ""
    echo "- measured: $today"
    echo "- rontolisp: $version (\`$commit\`)"
    echo "- gzip: \`gzip -9 -n\` (what Cloudflare counts against the 3 MB compressed bundle limit)"
    echo ""
    echo "| Worker | Flags | raw (B) | gzip (B) | % of the 3 MB limit |"
    echo "| --- | --- | ---: | ---: | ---: |"
    local n=${#names[@]}
    for ((i = 0; i < n; i++)); do
      local pct
      pct="$(awk -v g="${gzips[i]}" 'BEGIN { printf "%.1f", g * 100 / (3 * 1024 * 1024) }')"
      printf '| %s | `%s` | %s | %s | %s%% |\n' \
        "${names[i]}" "${flagsv[i]}" "$(commas "${raws[i]}")" "$(commas "${gzips[i]}")" "$pct"
    done
    if [[ -n "$jco_js" ]]; then
      echo ""
      echo "The component row is the core module alone. Reached through \`jco transpile\`"
      echo "a Worker also imports the generated JavaScript: **$(commas "$jco_js") B** of it."
    fi
    echo ""
    cat "$notes/cloudflare-workers.md"
  } >"$f"
  echo "wrote $f"
}

wasmtime_version=""
if [[ "$have_wasmtime" == 1 ]]; then
  wasmtime_version="$(wasmtime --version 2>/dev/null | head -1)"
fi

if [[ "$what" == "all" || "$what" == "wasm" ]]; then
  measure_wasm_family
fi
if [[ "$what" == "all" || "$what" == "workers" ]]; then
  measure_workers_family
fi

# --- results/sizes.json -----------------------------------------------------
# Only a full run can claim to be the whole picture, so a partial run leaves the
# machine-readable file alone rather than truncating it.
if [[ "$what" == "all" ]]; then
  {
    echo "{"
    echo "  \"generated\": \"$today\","
    echo "  \"rontolisp\": \"$version\","
    echo "  \"commit\": \"$commit\","
    echo "  \"wasmtime\": \"${wasmtime_version:-}\","
    echo "  \"artifacts\": ["
    for ((i = 0; i < ${#json_rows[@]}; i++)); do
      printf '    %s%s\n' "${json_rows[i]}" "$([[ $i -lt $((${#json_rows[@]} - 1)) ]] && echo , || true)"
    done
    echo "  ]"
    echo "}"
  } >"$results/sizes.json"
  echo "wrote $results/sizes.json"
fi

echo ""
echo "done."
