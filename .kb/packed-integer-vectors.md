# Packed integer vectors ((unsigned-byte 8|16|32) rank-1 arrays)

**Invariant: `(make-array n :element-type '(unsigned-byte 8|16|32))` (rank 1, no
fill pointer / adjustability / displacement, LITERAL element type) builds a
PACKED unsigned-integer vector with identical element semantics on every
backend: a store MASKS the value to the element width (two's-complement
truncation -- exactly what raw i8/i16/i32 storage does), a read returns the
stored value widened UNSIGNED, and a non-integer store is a type error
(interpreter/JVM signal, WASM traps).** Any other make-array combination --
rank-n, `:fill-pointer`, `:adjustable`, `:displaced-to`, a non-literal or other
element type -- keeps the general boxed representation, mirroring the packed
float arrays' fallback rule. Introduced by todo 194 stage 2 so ironclad's
SHA-256 working buffers stay unboxed on the wasm-GC backend.

## Representation per backend

- **Interpreter**: `LispIntVector` (`am.ik.rontolisp`) -- `int width` (8/16/32)
  + pre-masked `long[]` data. A new member of the sealed `LispVal`.
- **JVM**: a bare `long[]` with a width header -- `long[]{width, e0, ...}`
  (width 8/16/32 at index 0, pre-masked elements from index 1; `instanceof
  long[]` is the free discriminator, disjoint from `int[]` charboxes,
  `double[]`/`float[]` packed floats, `Object[]` and `ArrayList`). Runtime
  helpers `_iv*` in `JvmIntArrayRuntimeBuilder` (`_ivAref1`/`_ivAset1`/
  `_ivDims`/`_ivLength`/`_ivToGeneral`/`_ivElementType`/`_ivMake`/`_ivAlike`/
  `_ivRequireGeneral`), emitted under the `Ctx.usesIntArray` gate (a
  `LispIntVector` literal or a literal packed `:element-type`; forces
  `usesArrays`; gate off = byte-identical build). Accessor dispatch chains
  iv -> fv -> general, matching the float template; `_ivMake` does the
  runtime rank check (rank-n falls back to `_arrayMake`).
- **wasm-GC** (Preview 1 AND `--component`): the BARE
  `TYPE_I8ARR/TYPE_I16ARR/TYPE_I32ARR` array value itself --
  `(array (mut i8|i16|i32))`, types 57-59 in ONE rec group (keeps the i32
  width structurally distinct from `TYPE_LIMBS` under wasm-GC
  canonicalization), no struct wrapper, no dims (`array.len` is the length;
  rank-1 only). `ref.test` discriminates the width directly.
  `TYPE_IV_SET` (60) is the `_iv_set` store-helper signature; the
  `--simd`/async/instance blocks shift past all four via `IARR_TYPE_LAST`.
  `FUNC_IV_SET` sits after `FUNC_FX_REM` (`FX_FUNC_LAST` now includes it;
  `FUNC_VEC_BASE`/`FUNC_USER_BASE` rebase as before).
- **`--no-gc`**: arrays are unsupported on the scalar backend as before (the
  eligibility scan's clear compile error).

## Semantics shared by all backends (pinned by tests)

- `aref`/`(setf aref)` (rank-1), `row-major-aref`/`%row-major-aset`: masked
  store returns the value AS STORED (read back unsigned); out-of-range index
  errors (WASM: `array.get/set` trap). A `TYPE_BIGINT`-tier store contributes
  its LOW 32 bits on every backend (interpreter `BigInteger.longValue()`
  masking == the wasm `_limb_get(limbs, 0)` arm).
- `length`, `array-dimensions` (= `(n)`), `arrayp`/`vectorp`/`%arrayp` true,
  `array-element-type` returns the REAL `(unsigned-byte N)` list (general
  arrays still answer `t`).
- `typep` for `(simple-array (unsigned-byte 8) (*))`-style specs keeps
  answering through `%arrayp` (element type not checked), so ironclad's
  `check-type`/`typep` guards pass unchanged.
- Printing: a plain `#(1 2 3)` vector at every width (CL prints specialized
  vectors this way; reading it back yields a general vector, which is
  conformant). WASM printer converts to a boxed general array in place and
  reuses the general renderer (the farray pattern).
- `subseq` / `copy-seq` are TYPE-PRESERVING (packed in, packed out at the same
  width): the shared `expandSubseqCompat` vector lowering now allocates through
  the new internal `%array-alike` (fresh zero-filled array with the SAME
  representation as its first argument; `LispNames.ARRAY_ALIKE`, in
  `CL_INTERNALS`). `replace` mask-stores element-wise into a packed target.
  Other sequence functions (`reverse`, `remove`, `coerce`, `concatenate`, `map
  'vector`) return GENERAL vectors on every backend -- "vector in, vector out"
  with packing preserved only by the dedicated arms; the interpreter's
  `seqResult` deliberately rebuilds general to match the compile backends.
- Fill-pointer surface / `adjust-array` / displacement MUTATORS: clear "not
  applicable to a packed integer vector" errors (`requireGeneralArray` /
  `_ivRequireGeneral`), like the packed float arrays. **Unpinned edge, mirrors
  the pre-existing float-array shape**: the read-only probes
  (`array-has-fill-pointer-p`, `adjustable-array-p`, the displacement
  accessors) answer `nil`/`0` on the interpreter but error on the JVM and trap
  on wasm -- keep them out of ci-spec until someone needs them aligned.
- `equal`/`eql`: identity only (like every array).
- eq-hash: WASM hashes to the default bucket (identity semantics preserved).

## The reader literal

`#N@(...)` (ironclad's array-reader dispatch syntax, `.kb/reader-features.md`)
now reads into a packed vector for N in {8, 16, 32} (elements masked); any
other width keeps reading as a plain `#(...)` vector. `Token.IntVectorOpen`,
`LispReader.readIntVector`. Both compile backends bake the literal natively
(`WasmQuoteCompiler.compileIntVectorLiteral`, zero elements skip their store;
`JvmQuoteCompiler.compileLiteralIntVector`, a raw `long[]` build).

## The unboxed fast paths (wasm-GC; why this representation exists)

See `.kb/wasm-int-fusion.md` for the fused-tree machinery. The packed arms:

- An `(aref a i)` inside a fused tree is an `ArefLeaf`: array/index evaluate
  once into scratch locals; the fast path guards `testIntVector` + i31 index
  and reads `array.get_u` -> raw i64 (no `_int_new`, i.e. no `TYPE_BIGNUM`
  allocation for out-of-i31 u32 elements); any other array shape bails to the
  fallback, which reruns the ordinary rank-1 aref dispatch
  (`WasmArrayCompiler.emitAref1FromSlots`) from the SAME locals.
- `(setf (aref packed i) <integer tree>)` compiles the value RAW
  (`WasmIntFusionCompiler.tryCompileRaw` -- single ops qualify) and stores
  through `_iv_set` (width dispatch + wrap truncation); in statement position
  (`WasmExprCompiler.compileForEffect`, used by progn/while/tagbody statement
  slots) the value-as-stored is not materialized at all -- the hot-loop store
  allocates NOTHING. In value position the result re-reads and boxes.
- Fused-call defun inlining: a UNIQUELY-defined fixed-arity defun whose single
  body expression is a CLOSED integer-op tree over its parameters (whitelist:
  `+ - * mod rem logand logior logxor lognot ash 1+ 1- ldb byte aref`;
  `WasmIntFusionCompiler.isInlinableDefun`, collected in
  `WasmLispCompiler.compile`, `Ctx.inlinableDefuns`, NEVER under `--dynamic`)
  is substituted into fused trees AND fused at its own direct call sites: a
  parameter used once takes the argument's tree (fusion continues through), a
  parameter used more than once takes the argument demoted to a shared
  once-evaluated leaf. Leaf registration happens at classify time in source
  order, so argument evaluation order is preserved. This is what un-chops
  ironclad's `mod32+`/`rol32` one-liners out of the hot rounds. `(ldb (byte s
  p) x)` with a literal byte spec classifies through its `expandLdb` expansion.

## The heap-type encoding landmine this uncovered

Adding the four type entries pushed `TYPE_INSTANCE` in an async component to
index 65, which exposed a latent `am.ik.wasm` bug: `WasmWriter.writeHeapType`
treated ANY value >= 0x40 (64) as an abstract heap-type code and emitted the
negative single-byte form -- so `ref.test`/`ref.cast`/`ref.null` of type
indices 64+ produced "invalid heap type" modules (every async/serve component
failed to validate). Fixed by moving the abstract-code boundary to 0x60 (the
real abstract range starts at `exn` 0x69); type indices up to 95 now encode
correctly. **Re-evaluation trigger: if `ref.test`/`ref.cast` targets ever reach
type index 96, the int-parameter disambiguation must be replaced with separate
writeAbstractHeapType/writeTypeIndex entry points.** The runtime's fixed types
top out in the 60s today.

## Status (2026-07-27)

All four backends implemented (interpreter `LispIntVector`, JVM `long[]`
`_iv*` helpers, wasm Preview 1 + `--component` bare arrays; `--no-gc` rejects
arrays as before), byte-identical on the parity matrix and pinned by the
`packedIntVector*` tests in `LispEvaluatorTest` / `JvmLispCompilerTest` /
`WasmLispCompilerIntegrationTest` and the `packed-integer-vectors` ci-spec
case. PBKDF2-HMAC-SHA256 4096 rounds: 2.0 s -> **~1.45-1.5 s** on both wasm
backends (JVM 0.69 s), hashes identical everywhere. Stage 3 (flet inlining,
unboxed dual-representation locals, the masked-wrap peephole, the cached-`t`
global) then took it to **~0.93 s** -- see `.kb/wasm-int-fusion.md` and
`.kb/wasm-unboxed-locals.md`.
