#!/usr/bin/env bash
# Run every benchmark in programs/ on every Common Lisp implementation on this
# machine and regenerate results/.
#
# Usage:
#   ./measure.sh                      # every implementation found, rewrite results/
#   ./measure.sh sbcl rontolisp-jvm   # only those implementations
#   ./measure.sh clean                # remove out/
#
# Knobs:
#   RONTOLISP=/path/to/rontolisp   an explicit compiler, instead of the search below
#   BENCH_REPS=3                   runs per cell; the FASTEST is reported
#   BENCH_TIMEOUT=120              seconds one run may take before it is a `timeout`
#   BENCH_ONLY=fib,sieve           restrict to these benchmarks
#
# The rontolisp compiler is picked in this order:
#   $RONTOLISP            # an explicit path, if you set one
#   target/rontolisp      # the GraalVM native binary
#   target/...-exec.jar   # the executable jar, run with `java -jar`
#
# An implementation that is not installed is reported as `-` rather than dropped
# from the table, so the report can be read for what it did NOT measure too.
set -euo pipefail
# EPOCHREALTIME's decimal separator is the locale's, and it is parsed below, not
# only printed.
export LC_ALL=C

here="$(cd "$(dirname "$0")" && pwd)"
script_path="$here/$(basename "$0")"
repo_root="$(cd "$here/.." && pwd)"
out="$here/out"
results="$here/results"
# The prose half of the report -- what each benchmark measures and how to read
# the numbers -- appended verbatim below the generated tables, so a regenerated
# results/ file still explains itself. Edit notes/, never results/.
notes="$here/notes"

reps="${BENCH_REPS:-3}"
timeout_s="${BENCH_TIMEOUT:-120}"

if [[ "${1:-}" == "clean" ]]; then
  rm -rf "$out"
  echo "removed $out"
  exit 0
fi

# ============================================================================
# The implementations
# ============================================================================
# Every implementation is measured the same way: BUILD a compiled artifact from
# the source, then RUN that artifact. Nothing here loads a benchmark as source
# -- ECL would fall back to its bytecode interpreter and ABCL to its evaluator,
# and the row would then measure a mode nobody deploys (7x on ECL, 6x on ABCL).
# The rontolisp interpreter is the one deliberate exception: interpreting IS
# its mode, so its build step is empty and its build column reads `n/a`.
all_impls=(rontolisp rontolisp-jvm rontolisp-wasm sbcl ecl abcl)

impl_label() {
  case "$1" in
    rontolisp) printf 'rontolisp (interp)' ;;
    rontolisp-jvm) printf 'rontolisp (jvm)' ;;
    rontolisp-wasm) printf 'rontolisp (wasm)' ;;
    *) printf '%s' "$1" ;;
  esac
}

# --- locating rontolisp -----------------------------------------------------
ronto=()
if [[ -n "${RONTOLISP:-}" ]]; then
  ronto=("$RONTOLISP")
elif [[ -x "$repo_root/target/rontolisp" ]]; then
  ronto=("$repo_root/target/rontolisp")
elif [[ -f "$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar" ]]; then
  ronto=(java -jar "$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar")
fi

impl_available() {
  case "$1" in
    rontolisp | rontolisp-jvm) [[ ${#ronto[@]} -gt 0 ]] ;;
    rontolisp-wasm) [[ ${#ronto[@]} -gt 0 ]] && command -v wasmtime >/dev/null 2>&1 ;;
    sbcl) command -v sbcl >/dev/null 2>&1 ;;
    ecl) command -v ecl >/dev/null 2>&1 ;;
    abcl) command -v abcl >/dev/null 2>&1 ;;
  esac
}

# The version string each implementation answers with -- taken from the
# implementation itself rather than from the package manager that installed it.
impl_version() {
  case "$1" in
    rontolisp*)
      local v
      v="$("${ronto[@]}" -v 2>/dev/null | sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' | head -1)"
      printf 'rontolisp %s' "${v:-unknown}"
      ;;
    sbcl) printf 'SBCL %s' "$(sbcl --version 2>/dev/null | awk '{print $2}')" ;;
    ecl) ecl --version 2>/dev/null | head -1 ;;
    abcl)
      abcl --noinform --batch --eval \
        '(format t "~a ~a" (lisp-implementation-type) (lisp-implementation-version))' 2>/dev/null | tail -1
      ;;
  esac
}

# The extra runtime a row is really measured on: the JVM under ABCL and under
# both rontolisp JVM-side modes, wasmtime under the wasm mode.
impl_host() {
  case "$1" in
    rontolisp | rontolisp-jvm | abcl) java -version 2>&1 | head -1 ;;
    rontolisp-wasm) wasmtime --version 2>/dev/null | head -1 ;;
    *) printf 'native' ;;
  esac
}

# --- build ------------------------------------------------------------------
# $1 implementation, $2 benchmark name, $3 source path. Writes its artifact
# under $out/$impl/; a non-zero exit is a build failure and the cell reads `n/a`.
impl_build() {
  local impl="$1" prog="$2" src="$3" d="$out/$1"
  mkdir -p "$d"
  case "$impl" in
    rontolisp)
      : # interpreted: the source IS the artifact
      ;;
    rontolisp-jvm)
      # --class-name: the class is otherwise named after the file, and every
      # benchmark would then need its own -cp entry to stay distinguishable.
      "${ronto[@]}" "$src" -o "$d/Bench.class" --class-name Bench
      ;;
    rontolisp-wasm)
      "${ronto[@]}" "$src" -o "$d/$prog.wasm"
      ;;
    sbcl)
      sbcl --script /dev/stdin <<<"(compile-file \"$src\" :output-file \"$d/$prog.fasl\"
                                                 :print nil :verbose nil)"
      ;;
    ecl)
      # ECL's compile-file shells out to the C compiler, so this is the one
      # step in the whole harness that needs cc on PATH.
      ecl --norc --eval "(progn (compile-file \"$src\" :output-file \"$d/$prog.fas\"
                                              :print nil :verbose nil)
                                (ext:quit))" >/dev/null
      ;;
    abcl)
      # :output-file must be ABSOLUTE: ABCL resolves a relative one against the
      # SOURCE's directory, which would litter programs/ with .abcl files.
      abcl --noinform --batch --eval \
        "(compile-file \"$src\" :output-file \"$d/$prog.abcl\" :print nil :verbose nil)" >/dev/null
      ;;
  esac
}

# --- run --------------------------------------------------------------------
# $1 implementation, $2 benchmark name, $3 source path. EXECs the
# implementation, so the `timeout` in the parent signals the implementation's
# own process rather than a shell that would leave it orphaned.
impl_run() {
  local impl="$1" prog="$2" src="$3" d="$out/$1"
  case "$impl" in
    rontolisp) exec "${ronto[@]}" "$src" ;;
    rontolisp-jvm) exec java -cp "$d" Bench ;;
    rontolisp-wasm) exec wasmtime run -W gc "$d/$prog.wasm" ;;
    sbcl) exec sbcl --script /dev/stdin <<<"(load \"$d/$prog.fasl\")" ;;
    ecl) exec ecl --norc --eval "(progn (load \"$d/$prog.fas\") (ext:quit))" ;;
    abcl) exec abcl --noinform --batch --eval "(load \"$d/$prog.abcl\")" ;;
  esac
}

# One run of one benchmark, re-entered as a child process. Everything above this
# line is setup the child needs too, which is why the dispatch sits here rather
# than at the top of the file.
if [[ "${1:-}" == "--run" ]]; then
  impl_run "$2" "$3" "$4"
  exit
fi

timed_run() {
  timeout "$timeout_s" "$script_path" --run "$1" "$2" "$3"
}

# ============================================================================
# The benchmarks
# ============================================================================
# One row per file in programs/, in the order the report lists them. EXPECTED is
# what every implementation must print: the same everywhere by construction (no
# RANDOM, no float printing, no hash-table iteration order), so a cell that
# disagrees is a bug in an implementation and not a slower way to be right.
# NAME|EXPECTED RESULT
programs=(
  "fib|5702887"
  "mandelbrot|27159"
  "matmul|9988"
  "sieve|148933"
  "sort|3224631992"
  "hash|3839999200000"
  "string|26430000"
  "clos|52500000"
  "bignum|1455936"
  "list|4800000"
)

if [[ -n "${BENCH_ONLY:-}" ]]; then
  keep=()
  for row in "${programs[@]}"; do
    case ",${BENCH_ONLY}," in
      *",${row%%|*},"*) keep+=("$row") ;;
    esac
  done
  [[ ${#keep[@]} -gt 0 ]] || {
    echo "BENCH_ONLY matched no benchmark" >&2
    exit 2
  }
  programs=("${keep[@]}")
fi

# ============================================================================
# Helpers
# ============================================================================
now_ms() {
  if [[ -n "${EPOCHREALTIME:-}" ]]; then
    # "1756312345.678901" -> milliseconds, without spawning anything.
    printf '%s' "$(((${EPOCHREALTIME%%.*} * 1000) + (10#${EPOCHREALTIME#*.} / 1000)))"
  else
    printf '%s' "$(($(date +%s%N) / 1000000))"
  fi
}

commas() { printf '%s' "$1" | rev | sed 's/.\{3\}/&,/g' | rev | sed 's/^,//'; }

# A version banner is whatever the implementation felt like printing, and
# `java -version` prints an embedded quoted version number -- so nothing goes
# into the JSON without being escaped for it first.
json_escape() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }

# ============================================================================
# The measurement
# ============================================================================
requested=("$@")
if [[ ${#requested[@]} -eq 0 ]]; then
  requested=("${all_impls[@]}")
else
  for impl in "${requested[@]}"; do
    case " ${all_impls[*]} " in
      *" $impl "*) ;;
      *)
        echo "unknown implementation '$impl' -- one of: ${all_impls[*]}" >&2
        exit 2
        ;;
    esac
  done
fi

declare -A run_ms build_ms cell_note have version host

for impl in "${requested[@]}"; do
  if impl_available "$impl"; then
    have[$impl]=1
    version[$impl]="$(impl_version "$impl")"
    host[$impl]="$(impl_host "$impl")"
    printf 'found %-20s %s\n' "$(impl_label "$impl")" "${version[$impl]}"
  else
    have[$impl]=0
    printf 'NOT INSTALLED: %s\n' "$(impl_label "$impl")"
  fi
done
[[ ${#ronto[@]} -gt 0 ]] ||
  echo "NOTE: no rontolisp binary or jar -- build one with ./mvnw -DskipTests package"

# The startup tax, measured once per implementation on a program that computes
# nothing: it is a property of the implementation, not of any benchmark, so it
# gets its own row instead of being smeared across ten of them. Generated rather
# than kept in programs/, because it is not a benchmark.
mkdir -p "$out"
cat >"$out/noop.lisp" <<'NOOP'
(princ "result=0 ms=0")
(terpri)
NOOP

fail=0
for impl in "${requested[@]}"; do
  [[ "${have[$impl]}" == 1 ]] || continue
  echo ""
  echo "=== $(impl_label "$impl") ==="

  if impl_build "$impl" noop "$out/noop.lisp" >/dev/null 2>&1; then
    t0="$(now_ms)"
    if timed_run "$impl" noop "$out/noop.lisp" >/dev/null 2>&1; then
      run_ms["$impl|__startup"]=$(($(now_ms) - t0))
    fi
  fi
  printf '%-14s %s ms\n' "startup:" "${run_ms["$impl|__startup"]:-?}"

  for row in "${programs[@]}"; do
    prog="${row%%|*}"
    expected="${row#*|}"
    src="$here/programs/$prog.lisp"
    printf '%-14s ' "$prog:"

    t0="$(now_ms)"
    if ! impl_build "$impl" "$prog" "$src" >"$out/$impl-$prog.build.log" 2>&1; then
      cell_note["$impl|$prog"]="n/a"
      echo "BUILD FAILED (see out/$impl-$prog.build.log)"
      fail=1
      continue
    fi
    build_ms["$impl|$prog"]=$(($(now_ms) - t0))

    best=""
    last_wall=""
    for ((r = 0; r < reps; r++)); do
      t0="$(now_ms)"
      if ! timed_run "$impl" "$prog" "$src" \
        >"$out/$impl-$prog.out" 2>"$out/$impl-$prog.err"; then
        # A cell that cannot finish inside the budget is not retried: the
        # remaining repetitions would spend the same wall clock to learn the
        # same thing. A LATER repetition timing out after an earlier one
        # finished keeps the measurement -- one slow run does not unmeasure a
        # fast one.
        [[ -n "$best" ]] || cell_note["$impl|$prog"]="timeout"
        break
      fi
      wall=$(($(now_ms) - t0))
      line="$(grep -m1 '^result=' "$out/$impl-$prog.out" || true)"
      if [[ -z "$line" ]]; then
        cell_note["$impl|$prog"]="no output"
        break
      fi
      got="${line#result=}"
      got="${got%% *}"
      if [[ "$got" != "$expected" ]]; then
        cell_note["$impl|$prog"]="wrong answer"
        echo "WRONG ANSWER (expected $expected, got $got)"
        fail=1
        break
      fi
      ms="${line##*ms=}"
      # The in-program number is what gets reported; the wall clock is kept only
      # so the console line shows when startup dwarfs the work.
      if [[ -z "$best" || "$ms" -lt "$best" ]]; then best="$ms"; fi
      last_wall="$wall"
    done

    if [[ -z "$best" ]]; then
      [[ "${cell_note["$impl|$prog"]:-}" == "wrong answer" ]] ||
        echo "${cell_note["$impl|$prog"]:-no result}"
      continue
    fi
    printf '%s ms (best of %s, %s ms wall)\n' "$best" "$reps" "${last_wall:-?}"
    run_ms["$impl|$prog"]="$best"
  done
done

[[ "$fail" -eq 0 ]] || {
  echo "" >&2
  echo "ERROR: a benchmark failed to build or answered wrongly -- see the log above." >&2
  exit 1
}

# ============================================================================
# results/
# ============================================================================
# Only a full run can claim to be the whole picture, so a narrowed one prints
# its numbers and leaves results/ alone rather than overwriting a complete
# report with a partial table. Narrowing is for iterating, and iterating should
# not cost the last full measurement.
if [[ ${#requested[@]} -ne ${#all_impls[@]} || -n "${BENCH_ONLY:-}" ]]; then
  echo ""
  echo "partial run (implementations: ${requested[*]}${BENCH_ONLY:+, benchmarks: $BENCH_ONLY})"
  echo "-- results/ left as it was; run ./measure.sh with no arguments to rewrite it."
  exit 0
fi

mkdir -p "$results"
today="$(date -u +%Y-%m-%d)"
# --short=7, not --short: git scales the default abbreviation to the local
# object count, so a laptop and the CI runner would stamp the same commit at
# different lengths and every hand run would conflict with the scheduled one.
commit="$(git -C "$repo_root" rev-parse --short=7 HEAD 2>/dev/null || echo unknown)"

cell() {
  local impl="$1" prog="$2"
  if [[ "${have[$impl]:-0}" != 1 ]]; then
    printf -- '-'
  elif [[ -n "${run_ms["$impl|$prog"]:-}" ]]; then
    commas "${run_ms["$impl|$prog"]}"
  else
    printf '%s' "${cell_note["$impl|$prog"]:--}"
  fi
}

f="$results/benchmarks.md"
{
  echo "# Common Lisp implementation benchmarks"
  echo ""
  echo "Generated by \`bench-report/measure.sh\` -- do not edit by hand;"
  echo "the prose below the tables is [\`../notes/benchmarks.md\`](../notes/benchmarks.md)."
  echo "How the report is built and run: [../README.md](../README.md)."
  echo ""
  echo "- measured: $today"
  echo "- rontolisp commit: \`$commit\`"
  echo "- best of $reps runs per cell, ${timeout_s}s budget each"
  echo ""
  echo "| Implementation | Version | Runs on |"
  echo "| --- | --- | --- |"
  for impl in "${requested[@]}"; do
    if [[ "${have[$impl]}" == 1 ]]; then
      printf '| %s | %s | %s |\n' \
        "$(impl_label "$impl")" "${version[$impl]}" "${host[$impl]}"
    else
      printf '| %s | not installed | -- |\n' "$(impl_label "$impl")"
    fi
  done
  echo ""

  echo "## Run time (ms, lower is better)"
  echo ""
  printf '| Benchmark |'
  for impl in "${requested[@]}"; do printf ' %s |' "$(impl_label "$impl")"; done
  echo ""
  printf '| --- |'
  for _ in "${requested[@]}"; do printf ' ---: |'; done
  echo ""
  for row in "${programs[@]}"; do
    prog="${row%%|*}"
    printf '| [%s](../programs/%s.lisp) |' "$prog" "$prog"
    for impl in "${requested[@]}"; do printf ' %s |' "$(cell "$impl" "$prog")"; done
    echo ""
  done
  printf '| **startup** |'
  for impl in "${requested[@]}"; do
    if [[ -n "${run_ms["$impl|__startup"]:-}" ]]; then
      printf ' %s |' "$(commas "${run_ms["$impl|__startup"]}")"
    else
      printf ' - |'
    fi
  done
  echo ""
  echo ""
  echo "\`startup\` is the whole process, wall clock, for a program that computes"
  echo "nothing. Every other row is the benchmark timing ITSELF -- the program"
  echo "reads the clock either side of its own work -- so no other row contains it."
  echo ""

  # A ratio table only means something when there is a baseline to divide by.
  if [[ "${have[sbcl]:-0}" == 1 ]]; then
    echo "## Relative to SBCL (x, lower is better)"
    echo ""
    printf '| Benchmark |'
    for impl in "${requested[@]}"; do
      [[ "$impl" == "sbcl" ]] || printf ' %s |' "$(impl_label "$impl")"
    done
    echo ""
    printf '| --- |'
    for impl in "${requested[@]}"; do
      [[ "$impl" == "sbcl" ]] || printf ' ---: |'
    done
    echo ""
    for row in "${programs[@]}"; do
      prog="${row%%|*}"
      base="${run_ms["sbcl|$prog"]:-}"
      printf '| %s |' "$prog"
      for impl in "${requested[@]}"; do
        [[ "$impl" == "sbcl" ]] && continue
        mine="${run_ms["$impl|$prog"]:-}"
        if [[ -n "$base" && -n "$mine" && "$base" -gt 0 ]]; then
          printf ' %s |' "$(awk -v a="$mine" -v b="$base" 'BEGIN { printf "%.2f", a / b }')"
        else
          printf ' %s |' "$(cell "$impl" "$prog")"
        fi
      done
      echo ""
    done
    echo ""
  fi

  echo "## Build time (ms: source to the artifact that was then run)"
  echo ""
  printf '| Benchmark |'
  for impl in "${requested[@]}"; do printf ' %s |' "$(impl_label "$impl")"; done
  echo ""
  printf '| --- |'
  for _ in "${requested[@]}"; do printf ' ---: |'; done
  echo ""
  for row in "${programs[@]}"; do
    prog="${row%%|*}"
    printf '| %s |' "$prog"
    for impl in "${requested[@]}"; do
      if [[ "${have[$impl]}" != 1 ]]; then
        printf ' - |'
      elif [[ "$impl" == "rontolisp" ]]; then
        printf ' n/a |'
      elif [[ -n "${build_ms["$impl|$prog"]:-}" ]]; then
        printf ' %s |' "$(commas "${build_ms["$impl|$prog"]}")"
      else
        printf ' - |'
      fi
    done
    echo ""
  done
  echo ""
  echo "The rontolisp interpreter has no build column: interpreting the source is"
  echo "its mode, so there is no artifact between the two."
  echo ""
  cat "$notes/benchmarks.md"
} >"$f"
echo ""
echo "wrote $f"

# --- results/benchmarks.json ------------------------------------------------
{
  echo "{"
  echo "  \"generated\": \"$today\","
  echo "  \"commit\": \"$commit\","
  echo "  \"reps\": $reps,"
  echo "  \"timeout_seconds\": $timeout_s,"
  echo "  \"implementations\": {"
  n=${#requested[@]}
  i=0
  for impl in "${requested[@]}"; do
    i=$((i + 1))
    printf '    "%s": {"version": "%s", "host": "%s", "startup_ms": %s}%s\n' \
      "$impl" "$(json_escape "${version[$impl]:-}")" "$(json_escape "${host[$impl]:-}")" \
      "${run_ms["$impl|__startup"]:-null}" \
      "$([[ $i -lt $n ]] && echo , || true)"
  done
  echo "  },"
  echo "  \"benchmarks\": ["
  rows=()
  for row in "${programs[@]}"; do
    prog="${row%%|*}"
    for impl in "${requested[@]}"; do
      [[ "${have[$impl]}" == 1 ]] || continue
      # The interpreter's "build" is the empty case arm above, so its few
      # milliseconds are the harness measuring itself. It reads null here for
      # the same reason the table reads n/a.
      b="${build_ms["$impl|$prog"]:-null}"
      [[ "$impl" == "rontolisp" ]] && b=null
      rows+=("{\"benchmark\":\"$prog\",\"implementation\":\"$impl\",\"run_ms\":${run_ms["$impl|$prog"]:-null},\"build_ms\":$b,\"status\":\"${cell_note["$impl|$prog"]:-ok}\"}")
    done
  done
  for ((i = 0; i < ${#rows[@]}; i++)); do
    printf '    %s%s\n' "${rows[i]}" "$([[ $i -lt $((${#rows[@]} - 1)) ]] && echo , || true)"
  done
  echo "  ]"
  echo "}"
} >"$results/benchmarks.json"
echo "wrote $results/benchmarks.json"

echo ""
echo "done."
