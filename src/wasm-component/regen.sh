#!/usr/bin/env bash
# Regenerate the runtime WASI 0.2 component blobs that WasmComponentBuilder loads, from the
# sources in this directory, fully offline. Requires wasm-tools and python3 on PATH.
#
# Two variants are produced:
#   base (no HTTP, runnable without `-S http=y`):
#     <resources>/.../component/mem.wasm          <- mem.wat
#     <resources>/.../component/adapter.wasm      <- adapter.wat
#     <resources>/.../component/import-block.bin  <- uni.wit (world uni) + deps/ + core.wat
#   http (outgoing HTTP for rontolisp:fetch, needs `wasmtime run -S http=y`):
#     <resources>/.../component/mem-http.wasm          <- mem-http.wat (16 pages)
#     <resources>/.../component/adapter-http.wasm      <- adapter-http.wat (adds $fetch)
#     <resources>/.../component/import-block-http.bin  <- uni-http.wit (world uni-http) +
#                                                         deps/ (incl. deps/http) + core-http.wat
#
# After editing the sources, run this, then re-derive the wiring constants in
# WasmComponentBuilder.build from `wasm-tools dump` of the regenerated component (the
# component instance/type indices and per-function canonical options) and re-run the tests.
# Appending an interface LAST keeps existing indices stable -- EXCEPT that calling
# pollable.block materializes a wasi:io/poll instance which wasm-tools orders right after
# wasi:io/error, shifting the http variant's instance indices (handled in the builder).
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

echo "== base core modules =="
wasm-tools parse mem.wat     -o "$OUT/mem.wasm"
wasm-tools parse adapter.wat -o "$OUT/adapter.wasm"
wasm-tools validate "$OUT/mem.wasm"
wasm-tools validate "$OUT/adapter.wasm"

echo "== base unified import block =="
wasm-tools parse core.wat -o core.wasm
wasm-tools component embed . core.wasm -o embedded.wasm --world uni
wasm-tools component new embedded.wasm -o uni.wasm
wasm-tools validate -f component-model uni.wasm
slice_import_block uni.wasm "$OUT/import-block.bin"

echo "== http core modules =="
wasm-tools parse mem-http.wat     -o "$OUT/mem-http.wasm"
wasm-tools parse adapter-http.wat -o "$OUT/adapter-http.wasm"
wasm-tools validate "$OUT/mem-http.wasm"
wasm-tools validate "$OUT/adapter-http.wasm"

echo "== http unified import block =="
wasm-tools parse core-http.wat -o core-http.wasm
wasm-tools component embed . core-http.wasm -o embedded-http.wasm --world uni-http
wasm-tools component new embedded-http.wasm -o uni-http.wasm
wasm-tools validate -f component-model uni-http.wasm
slice_import_block uni-http.wasm "$OUT/import-block-http.bin"

rm -f core.wasm embedded.wasm uni.wasm core-http.wasm embedded-http.wasm uni-http.wasm
echo "== done =="
