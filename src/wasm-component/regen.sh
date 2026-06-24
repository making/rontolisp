#!/usr/bin/env bash
# Regenerate the runtime WASI 0.2 component blobs that WasmComponentBuilder loads, from the
# sources in this directory, fully offline. Requires wasm-tools and python3 on PATH.
#
#   <resources>/.../component/mem.wasm          <- mem.wat
#   <resources>/.../component/adapter.wasm      <- adapter.wat
#   <resources>/.../component/import-block.bin  <- uni.wit + deps/ (vendored WASI 0.2.0 WIT) + core.wat
#
# After editing uni.wit (e.g. adding an interface) or core.wat, run this, then re-derive
# the wiring constants in WasmComponentBuilder.build from `wasm-tools dump uni.wasm` (the
# component instance/type indices and per-function canonical options) and re-run the test
# suite. Appending an interface LAST keeps the existing indices stable.
set -euo pipefail
cd "$(dirname "$0")"
OUT=../main/resources/am/ik/rontolisp/codegen/wasm/component

echo "== core modules =="
wasm-tools parse mem.wat     -o "$OUT/mem.wasm"
wasm-tools parse adapter.wat -o "$OUT/adapter.wasm"
wasm-tools validate "$OUT/mem.wasm"
wasm-tools validate "$OUT/adapter.wasm"

echo "== unified import block =="
wasm-tools parse core.wat -o core.wasm
# Embed the WIT world into the stub core module, then turn it into a component.
wasm-tools component embed . core.wasm -o embedded.wasm --world uni
wasm-tools component new embedded.wasm -o uni.wasm
wasm-tools validate -f component-model uni.wasm

# import-block.bin = the component's type/import/alias sections: everything from just after
# the 8-byte preamble up to the first core-module section (component section id 1).
python3 - "$OUT/import-block.bin" <<'PY'
import sys
data = open("uni.wasm", "rb").read()
pos = 8  # skip preamble: magic(4) + version/layer(4)
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
    if sec_id == 1:  # first core-module section: end of the import block
        break
    pos = after + size
open(sys.argv[1], "wb").write(data[start:pos])
print(f"import-block.bin = uni.wasm[{start}:{pos}] = {pos-start} bytes")
PY

rm -f core.wasm embedded.wasm uni.wasm
echo "== done =="
