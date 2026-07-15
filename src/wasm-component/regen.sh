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
# mem-http-client.wasm (the 16-page memory module) is still regenerated here: the serve
# variants reuse it. Both rontolisp:fetch AND rontolisp:http-handler are Lisp libraries now
# (fetch.lisp / serve.lisp) over wit-imported wasi:http (eval/FetchLibrary, eval/ServeLibrary);
# the standalone http-client adapter, the serve adapter and the extended serve+fetch bridge
# are all gone. It keeps the base I/O on WASI 0.3 but adds the WASI 0.2 wasi:http + wasi:io
# machinery (async wasi:http@0.3 does not exist upstream yet; see ../../TODO.md). The 0.2 deps
# live alongside the 0.3 ones in deps/ under version-suffixed directories (clocks-0.2, io-0.2,
# http). After regenerating an import block, re-derive the fixed instance / type constants in
# WasmServeComponentBuilder (NARROW / WIDE) from `wasm-tools dump`.
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
wasm-tools parse mem-http-client.wat     -o "$OUT/mem-http-client.wasm"
wasm-tools parse adapter-sockets.wat -o "$OUT/adapter-sockets.wasm"
# The preview1 bridge is shared by plain serve AND serve+fetch: fetch is the fetch.lisp
# library over wit-imported wasi:http/outgoing-handler now, so the core imports no `http`
# function and needs no extended bridge or serve adapter.
wasm-tools parse adapter-http-server-p1.wat -o "$OUT/adapter-http-server-p1.wasm"
wasm-tools validate "$OUT/mem.wasm"
wasm-tools validate "$OUT/adapter.wasm"
wasm-tools validate "$OUT/mem-http-client.wasm"
wasm-tools validate "$OUT/adapter-sockets.wasm"
wasm-tools validate "$OUT/adapter-http-server-p1.wasm"

echo "== unified import block (base) =="
wasm-tools parse core.wat -o core.wasm
wasm-tools component embed . core.wasm -o embedded.wasm --world uni
wasm-tools component new embedded.wasm -o uni.wasm
wasm-tools validate -f component-model -f cm-async -f cm-async-stackful -f cm-more-async-builtins uni.wasm
slice_import_block uni.wasm "$OUT/import-block.bin"

echo "== unified import block (sockets variant) =="
wasm-tools parse core-sockets.wat -o core-sockets.wasm
wasm-tools component embed . core-sockets.wasm -o embedded-sockets.wasm --world uni-sockets
wasm-tools component new embedded-sockets.wasm -o uni-sockets.wasm
wasm-tools validate -f component-model -f cm-async -f cm-async-stackful -f cm-more-async-builtins uni-sockets.wasm
slice_import_block uni-sockets.wasm "$OUT/import-block-sockets.bin"

echo "== unified import block (serve variant: rontolisp:http-handler) =="
wasm-tools parse core-http-server.wat -o core-http-server.wasm
wasm-tools component embed . core-http-server.wasm -o embedded-http-server.wasm --world uni-http-server
wasm-tools component new embedded-http-server.wasm -o uni-http-server.wasm
wasm-tools validate -f component-model -f cm-async -f cm-async-stackful -f cm-more-async-builtins uni-http-server.wasm
slice_import_block uni-http-server.wasm "$OUT/import-block-http-server.bin"

echo "== unified import block (serve+fetch variant: http-handler + fetch) =="
wasm-tools parse core-http-server-client.wat -o core-http-server-client.wasm
wasm-tools component embed . core-http-server-client.wasm -o embedded-http-server-client.wasm --world uni-http-server-client
wasm-tools component new embedded-http-server-client.wasm -o uni-http-server-client.wasm
wasm-tools validate -f component-model -f cm-async -f cm-async-stackful -f cm-more-async-builtins uni-http-server-client.wasm
slice_import_block uni-http-server-client.wasm "$OUT/import-block-http-server-client.bin"

echo "== unified import block (--no-gc print micro-adapter: todo 93) =="
wasm-tools parse core-nogc-print.wat -o core-nogc-print.wasm
wasm-tools component embed . core-nogc-print.wasm -o embedded-nogc-print.wasm --world uni-nogc-print
wasm-tools component new embedded-nogc-print.wasm -o uni-nogc-print.wasm
wasm-tools validate -f component-model uni-nogc-print.wasm
slice_import_block uni-nogc-print.wasm "$OUT/import-block-nogc-print.bin"

rm -f core-nogc-print.wasm embedded-nogc-print.wasm uni-nogc-print.wasm \
      core.wasm embedded.wasm uni.wasm \
      core-sockets.wasm embedded-sockets.wasm uni-sockets.wasm \
      core-http-server.wasm embedded-http-server.wasm uni-http-server.wasm \
      core-http-server-client.wasm embedded-http-server-client.wasm uni-http-server-client.wasm
echo "== done =="
