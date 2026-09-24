#!/usr/bin/env bash
# Tests build.sh's static-link check against executables whose linkage is known: a
# static-pie and a dynamic one, for the host and, where a cross compiler is installed,
# for the other Linux architecture (ldd answers "not a dynamic executable" for ANY
# foreign-architecture file, and on aarch64 misreads a static-pie as dynamic).
set -euo pipefail
cd "$(dirname "$0")"
[[ $(uname -s) == Linux ]] || { echo "build-sh-test: Linux only, skipped"; exit 0; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
printf 'int main(void) { return 0; }\n' >"$work/main.c"

failures=0
expect() {
  local want=$1 file=$2 got=static
  ./build.sh --is-static "$file" || got=dynamic
  if [[ $got == "$want" ]]; then
    echo "ok   $want $(basename "$file")"
  else
    echo "FAIL $(basename "$file"): want $want, got $got"
    failures=$((failures + 1))
  fi
}

for cc in cc aarch64-linux-gnu-gcc x86_64-linux-gnu-gcc; do
  command -v "$cc" >/dev/null || continue
  if "$cc" -static-pie "$work/main.c" -o "$work/$cc-static" 2>/dev/null; then
    expect static "$work/$cc-static"
  fi
  "$cc" "$work/main.c" -o "$work/$cc-dynamic"
  expect dynamic "$work/$cc-dynamic"
done
exit $((failures > 0))
