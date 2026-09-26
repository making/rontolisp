# The EH-mode wasm car/cdr check pays a second type test on the hot path

Difficulty: High

Since 972 an EH-mode `car`/`cdr` site reads `local.get x; ref.test $cons; if (result eqref)
local.get x; ref.cast $cons; struct.get ... else local.get x; call _car end`, so a value
that is no list is `CAR`'s catchable type-error instead of a trapping cast. The cons path
now tests the type twice (`ref.test`, then `ref.cast`) where the unchecked shape tested
null and cast once. Measured 2026-09-26, wasmtime 47, a 1M-element list summed 40 times
with `(car l)`/`(cdr l)` in a `loop` (program in EH mode through a `handler-case`):
129 -> 166 ms (+28%). The same loop over `aref` and the JVM are unchanged; `--optimize=size`
sites were already a call to `_car` and pay nothing.

`br_on_cast_fail $miss (ref null eq) (ref $cons)` does it in one test (nil and a
non-list both take the miss arm to the checked `_car`). `am.ik.wasm` does not know the
opcode: `WasmCodeModel.decodeGc` throws on it, and every pass that follows branches --
`WasmInliner` (label depths), `WasmRefTypeFolder` (the type stack and the edge to the
label), `WasmPeephole` -- must model it before any site emits it.

Goal: the single-test shape at every checked site, the loop above back to the unchecked
time, `WasmTreeShakerCorpusTest` and the ci-spec E2E green. `.kb/cons-access-runtime.md`
holds the shapes.
