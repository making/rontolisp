#!/usr/bin/env bash
# Regenerate the runtime WASI 0.3 (Preview 3) component blobs that WasmComponentBuilder
# loads, from the sources in this directory, fully offline. Requires wasm-tools and python3
# on PATH.
#
#   <resources>/.../component/mem.wasm          <- mem.wat
#   <resources>/.../component/adapter.wasm      <- adapter.wat
#   <resources>/.../component/import-block.bin  <- uni.wit (world uni) + deps/ + core.wat
#
# After editing the sources, run this, then re-derive the wiring constants in
# WasmComponentBuilder.build from `wasm-tools dump` of a generated component (the component
# instance/type indices and per-function canonical options) and re-run the tests. Appending
# an interface LAST in uni.wit keeps existing indices stable.
#
# NOTE: the rontolisp:fetch HTTP variant has not yet been ported to WASI 0.3 / async
# wasi:http; that is tracked separately. Only the base variant is regenerated here.
set -euo pipefail
cd "$(dirname "$0")"
OUT=../main/resources/am/ik/rontolisp/codegen/wasm/component

# slice_import_block <component.wasm> <out.bin>
# import block = the component's type/import/alias sections: everything from just after the
# 8-byte preamble up to the first core-module section (component section id 1).
slice_import_block() {
  python3 - "$1" "$2" <<'PY'
import sys
data = open(sys.argv[1], "rb").read()
pos = 8
def leb(b, p):
    r = s = 0
    while True:
        x = b[p]; p += 1; r |= (x & 0x7f) << s
        if not (x & 0x80): return r, p
        s += 7
start = pos
while pos < len(data):
    sec_id = data[pos]
    size, after = leb(data, pos + 1)
    if sec_id == 1:
        break
    pos = after + size
open(sys.argv[2], "wb").write(data[start:pos])
print(f"{sys.argv[2]} = {sys.argv[1]}[{start}:{pos}] = {pos-start} bytes")
PY
}

echo "== core modules =="
wasm-tools parse mem.wat     -o "$OUT/mem.wasm"
wasm-tools parse adapter.wat -o "$OUT/adapter.wasm"
wasm-tools validate "$OUT/mem.wasm"
wasm-tools validate "$OUT/adapter.wasm"

echo "== unified import block =="
wasm-tools parse core.wat -o core.wasm
wasm-tools component embed . core.wasm -o embedded.wasm --world uni
wasm-tools component new embedded.wasm -o uni.wasm
wasm-tools validate -f component-model -f cm-async -f cm-async-stackful -f cm-more-async-builtins uni.wasm
slice_import_block uni.wasm "$OUT/import-block.bin"

rm -f core.wasm embedded.wasm uni.wasm
echo "== done =="
