#!/usr/bin/env bash
# Rebuilds the module that trips wasmtime's copying-collector defect and runs it at one
# pre-grow size. Measured 2026-09-24: 65259152 and 65300000 fail on 47.0.3 and 49.0.0,
# 65193616 / 65228384 / 65324688 are green (.kb/wasm-gc-heap-pregrow.md).
#
#   repro.sh <workdir> <pregrow-bytes> [wasmtime flags...]
#
# <workdir> gets a worktree of a34942ace~ (the last commit before the pre-grow was
# quantized), its ci-spec corpus compiled --simd (pre-grow 65,259,152 as emitted), the
# CorpusFixtures tree, and one patched module per size. WASMTIME picks the binary.
set -euo pipefail
work=$(realpath -m "$1"); size=$2; shift 2
repo=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
wt=${WASMTIME:-wasmtime}
mkdir -p "$work"
if [[ ! -d $work/old ]]; then
  git -C "$repo" worktree add -q --detach "$work/old" a34942ace~
  (cd "$work/old" && ./mvnw -q compile test-compile -Dspring-javaformat.skip=true)
fi
if [[ ! -f $work/old.wasm ]]; then
  python3 - "$work/old/src/test/resources/ci-spec.yaml" "$work/ci-program.lisp" <<'PY'
import sys, yaml
with open(sys.argv[2], "w") as o:
    for c in yaml.safe_load(open(sys.argv[1]))["cases"]:
        s = c["source"]; o.write(s if s.endswith("\n") else s + "\n")
PY
  java -cp "$work/old/target/classes" am.ik.rontolisp.cli.RontoLispCli "$work/ci-program.lisp" --simd -o "$work/old.wasm" 2>/dev/null
fi
run=$work/run-$size; rm -rf "$run"; mkdir -p "$run"
cat > "$work/Stage.java" <<'JAVA'
public class Stage { public static void main(String[] a) throws Exception {
  am.ik.rontolisp.testsupport.CorpusFixtures.stageWildPathnameTree(java.nio.file.Path.of(a[0]));
  am.ik.rontolisp.testsupport.CorpusFixtures.stageLnkFixture(java.nio.file.Path.of(a[0])); } }
JAVA
java -cp "$repo/target/test-classes:$repo/target/classes" "$work/Stage.java" "$run"
python3 - "$work/old.wasm" "$size" "$run/m.wasm" <<'PY'
import sys
def sleb(v):
    out = bytearray()
    while True:
        b = v & 0x7f; v >>= 7
        if (v == 0 and not b & 0x40) or (v == -1 and b & 0x40):
            out.append(b); return bytes(out)
        out.append(b | 0x80)
d = bytearray(open(sys.argv[1], "rb").read()); o = 19161
assert d[o] == 0x41 and d[o + 5] == 0xfb and d[o + 6] == 0x07, "pre-grow i32.const moved"
e = sleb(int(sys.argv[2])); assert len(e) == 4
d[o + 1:o + 5] = e
open(sys.argv[3], "wb").write(d)
PY
cd "$run"
set +e
"$wt" "$@" --wasm gc --wasm exceptions=y --dir . --dir /tmp m.wasm > out.txt 2> err.txt
rc=$?
echo "size=$size rc=$rc lines=$(wc -l < out.txt)"
grep -m2 -E "BUG|VMGcKind" err.txt
exit 0
