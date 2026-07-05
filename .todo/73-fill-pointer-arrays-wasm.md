# 73: Fill-pointer arrays on the WASM backend (+ `--no-gc` gate)

Split from `.todo/71`. Ports the fill-pointer sub-step to the WASM compiler
after `.todo/72` (JVM). See `.kb/adjustable-arrays.md`.

## Scope

Same surface as JVM: `make-array :fill-pointer/:adjustable`,
`fill-pointer`(+setf), `array-has-fill-pointer-p`, `adjustable-array-p`,
`array-element-type`, `vector-push`/`vector-pop`/`vector-push-extend`, with
`length` + the printer clamping to the fill pointer. Verify on BOTH Preview 1
and `--component`.

## Design

Today a WASM array is a `TYPE_CELL` box holding a header `TYPE_CONS`
`(dims . data)`, both `TYPE_HASH_BUCKETS` arrays of `(ref null eq)`
(`WasmArrayCompiler`, all inline -- no runtime helper, no new heap type, so the
static `FUNC_*` import indices stay identical across Preview 1 / `--component`).

Carry the fill pointer + adjustable flag by extending the header without a new
heap type -- e.g. the box holds `(meta . (dims . data))` where `meta` is a
2-slot buckets `[fillPointer-i31-or-null, adjustable-i31]`. Update every inline
site: `compileMake`, `compileAref`/`compileAset` (data now one cons deeper),
`compileDims`, `WasmLengthCompiler`, and the array printer. Keep it inline so the
component blobs are unaffected.

## `--no-gc`

`ScalarWasmCompiler` has no general array type (only string/char over
`[len][bytes]`). Fill-pointer vectors are a sharp edge here -- gate with a clear
compile error ("fill-pointer arrays require the GC backend") unless a scalar
lowering proves cheap. Document the limitation (todo-71 acceptance).

## Acceptance

`WasmLispCompilerIntegrationTest` cases mirroring the interpreter; output matches
across all four backends. Then add ci-spec cases + run native E2E
(`.kb/documentation-site.md` / CLAUDE.md "Verifying the Native Image") and the
per-operator doc pages (en+ja) -- the docs/ci-spec/E2E closeout for the whole
fill-pointer sub-step lands here.
