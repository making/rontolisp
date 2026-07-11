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
# The rontolisp:fetch HTTP variant is also regenerated here (import-block-http.bin /
# mem-http.wasm / adapter-http.wasm), from uni-http.wit (world uni-http) + core-http.wat.
# It keeps the base I/O on WASI 0.3 but adds the WASI 0.2 wasi:http + wasi:io machinery
# (async wasi:http@0.3 does not exist upstream yet; see ../../TODO.md). The 0.2 deps live
# alongside the 0.3 ones in deps/ under version-suffixed directories (clocks-0.2, io-0.2,
# http). After regenerating, re-derive the WasmComponentBuilder.buildHttp
# wiring constants from `wasm-tools dump`.
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
wasm-tools parse mem.wat          -o "$OUT/mem.wasm"
wasm-tools parse shim-nogc-print.wat   -o "$OUT/shim-nogc-print.wasm"
wasm-tools parse bridge-nogc-print.wat -o "$OUT/bridge-nogc-print.wasm"
wasm-tools parse fixup-nogc-print.wat  -o "$OUT/fixup-nogc-print.wasm"
wasm-tools validate "$OUT/shim-nogc-print.wasm"
wasm-tools validate "$OUT/bridge-nogc-print.wasm"
wasm-tools validate "$OUT/fixup-nogc-print.wasm"
wasm-tools parse adapter.wat      -o "$OUT/adapter.wasm"
wasm-tools parse mem-http.wat     -o "$OUT/mem-http.wasm"
wasm-tools parse adapter-http.wat -o "$OUT/adapter-http.wasm"
wasm-tools parse adapter-sock.wat -o "$OUT/adapter-sock.wasm"
wasm-tools parse adapter-serve.wat -o "$OUT/adapter-serve.wasm"
wasm-tools parse adapter-serve-p1.wat -o "$OUT/adapter-serve-p1.wasm"
wasm-tools parse adapter-serve-p1-http.wat -o "$OUT/adapter-serve-p1-http.wasm"
wasm-tools validate "$OUT/mem.wasm"
wasm-tools validate "$OUT/adapter.wasm"
wasm-tools validate "$OUT/mem-http.wasm"
wasm-tools validate "$OUT/adapter-http.wasm"
wasm-tools validate "$OUT/adapter-sock.wasm"
wasm-tools validate "$OUT/adapter-serve.wasm"
wasm-tools validate "$OUT/adapter-serve-p1.wasm"
wasm-tools validate "$OUT/adapter-serve-p1-http.wasm"

echo "== unified import block (base) =="
wasm-tools parse core.wat -o core.wasm
wasm-tools component embed . core.wasm -o embedded.wasm --world uni
wasm-tools component new embedded.wasm -o uni.wasm
wasm-tools validate -f component-model -f cm-async -f cm-async-stackful -f cm-more-async-builtins uni.wasm
slice_import_block uni.wasm "$OUT/import-block.bin"

echo "== unified import block (http variant) =="
wasm-tools parse core-http.wat -o core-http.wasm
wasm-tools component embed . core-http.wasm -o embedded-http.wasm --world uni-http
wasm-tools component new embedded-http.wasm -o uni-http.wasm
wasm-tools validate -f component-model -f cm-async -f cm-async-stackful -f cm-more-async-builtins uni-http.wasm
slice_import_block uni-http.wasm "$OUT/import-block-http.bin"

echo "== unified import block (sockets variant) =="
wasm-tools parse core-sock.wat -o core-sock.wasm
wasm-tools component embed . core-sock.wasm -o embedded-sock.wasm --world uni-sock
wasm-tools component new embedded-sock.wasm -o uni-sock.wasm
wasm-tools validate -f component-model -f cm-async -f cm-async-stackful -f cm-more-async-builtins uni-sock.wasm
slice_import_block uni-sock.wasm "$OUT/import-block-sock.bin"

echo "== unified import block (serve variant: rontolisp:http-handler) =="
wasm-tools parse core-serve.wat -o core-serve.wasm
wasm-tools component embed . core-serve.wasm -o embedded-serve.wasm --world uni-serve
wasm-tools component new embedded-serve.wasm -o uni-serve.wasm
wasm-tools validate -f component-model -f cm-async -f cm-async-stackful -f cm-more-async-builtins uni-serve.wasm
slice_import_block uni-serve.wasm "$OUT/import-block-serve.bin"

echo "== unified import block (serve+fetch variant: http-handler + fetch) =="
wasm-tools parse core-serve-http.wat -o core-serve-http.wasm
wasm-tools component embed . core-serve-http.wasm -o embedded-serve-http.wasm --world uni-serve-http
wasm-tools component new embedded-serve-http.wasm -o uni-serve-http.wasm
wasm-tools validate -f component-model -f cm-async -f cm-async-stackful -f cm-more-async-builtins uni-serve-http.wasm
slice_import_block uni-serve-http.wasm "$OUT/import-block-serve-http.bin"

echo "== unified import block (--no-gc print micro-adapter: todo 93) =="
wasm-tools parse core-nogc-print.wat -o core-nogc-print.wasm
wasm-tools component embed . core-nogc-print.wasm -o embedded-nogc-print.wasm --world uni-nogc-print
wasm-tools component new embedded-nogc-print.wasm -o uni-nogc-print.wasm
wasm-tools validate -f component-model uni-nogc-print.wasm
slice_import_block uni-nogc-print.wasm "$OUT/import-block-nogc-print.bin"

rm -f core-nogc-print.wasm embedded-nogc-print.wasm uni-nogc-print.wasm \
      core.wasm embedded.wasm uni.wasm core-http.wasm embedded-http.wasm uni-http.wasm \
      core-sock.wasm embedded-sock.wasm uni-sock.wasm \
      core-serve.wasm embedded-serve.wasm uni-serve.wasm \
      core-serve-http.wasm embedded-serve-http.wasm uni-serve-http.wasm
echo "== done =="
