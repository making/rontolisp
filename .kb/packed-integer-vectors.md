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

## "Literal" includes a `deftype` alias of one

**A zero-parameter `deftype` name in `:element-type` behaves exactly as if its
expansion had been written at the call site** -- on all four backends, for the
packed integer widths, the packed floats and the character vector alike.
`LispMacroExpander.resolveElementTypeAlias(spec, closRegistry)` is the one
resolver: it strips the quote the compile paths still carry, follows alias
chains (bounded at 16 hops, which is also what terminates a self-referential
`(deftype a () 'a)`), and returns the argument UNCHANGED when it names no
registered alias -- so a program with no alias compiles to the same bytes as
before. Three call sites, one per representation-choosing place:

- **Interpreter**: `Environment.makeArrayBuiltin(closRegistry)`, registered
  registry-less by `createGlobal` and re-registered by `LispEvaluator` with its
  own registry. Exactly the `concatenateBuiltin` arrangement, and for the same
  reason -- the registry is mutated as the program loads, so the builtin has to
  hold the live object rather than a snapshot.
- **JVM**: `JvmArrayCompiler.compileMake` resolves once, up front, and every
  recognizer below reads the resolved value. The program-level gates
  (`JvmLispCompiler.makeArrayIsPackedInt` / `makeArrayIsPackedFloat`, which
  decide whether the `_iv*` / `_fv*` helpers are emitted at all) resolve too:
  a gate that missed the alias would leave the helpers out and silently send the
  call to the general path.
- **wasm-GC**: `WasmArrayCompiler.compileMake`, same shape. This backend's
  `packedIntElementWidth` used to demand the quote wrapper, so it declined every
  alias the resolver had just expanded; it now accepts the bare list too, which
  is what the JVM's and the interpreter's have always done.

Found through salza2, which allocates every buffer as `:element-type 'octet`
(`(deftype octet () '(unsigned-byte 8))`). It got a general array of `nil`
instead of a packed vector of `0`, and its match scanner -- which compares an
element one past the copied input, relying on the buffer being zero-filled --
died on `Expected integer, got: NIL`. md5's `ub32` buffers and flexi-streams'
`octet` buffers are the same shape and pack now too (md5's digests are
unchanged; the mask-on-store is what its `(logand ... #xffffffff)` already did
by hand). `fixnum` (cl-ppcre, chipz) names no user deftype, so it is untouched.

**Still not resolved, deliberately**: a designator computed at RUN time
(`LispMacroExpander.lowerRuntimeElementTypeMakeArray`'s dispatch tests the value
against the character names only), and `with-open-file`/`open`'s
`:element-type`, whose `isBinaryElementTypeLiteral` check is a separate literal
matcher. Both are alias-blind; widen them when a library needs it. Pinned by
`LispEvaluatorTest.makeArrayElementTypeResolvesADeftypeAlias`, its
`Jvm`/`Wasm...IntegrationTest` twins, and the `make-array-element-type-deftype-alias`
ci-spec case.

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
- `typep` for `(simple-array (unsigned-byte 8) (*))`-style specs tests
  `%arrayp` AND `(equal (array-element-type x) '(unsigned-byte N))`, so a
  general `#(...)` is never a `(vector (unsigned-byte 8))` (s-sql's `sql-escape`
  dispatches on exactly that). The dimensions are not checked. This is why a
  `concatenate` that dropped the element type produced a value its own result
  type rejected (todo-262).
- Printing: a plain `#(1 2 3)` vector at every width (CL prints specialized
  vectors this way; reading it back yields a general vector, which is
  conformant). WASM printer converts to a boxed general array in place and
  reuses the general renderer (the farray pattern).
- `coerce` into an `(unsigned-byte 8|16|32)` vector designator builds the packed
  representation, the same arm `concatenate` has
  (`.kb/concatenate-result-families.md`); `map` and a COMPUTED coerce designator
  still do not, and that file carries the trigger.
- `subseq` / `copy-seq` are TYPE-PRESERVING (packed in, packed out at the same
  width): the shared `expandSubseqCompat` vector lowering now allocates through
  the new internal `%array-alike` (fresh zero-filled array with the SAME
  representation as its first argument; `LispNames.ARRAY_ALIKE`, in
  `CL_INTERNALS`). `replace` mask-stores element-wise into a packed target.
  `concatenate` packs when its RESULT TYPE asks for it -- an
  `(unsigned-byte 8|16|32)` element type in the designator, not the arguments'
  representation (todo-262, `.kb/concatenate-result-families.md`); the compile
  paths build it through the injected `%seq-int-vector` helper, the interpreter
  directly. The other sequence functions (`reverse`, `remove`, `coerce`, `map
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
(`WasmQuoteCompiler.compileIntVectorLiteral`;
`JvmQuoteCompiler.compileLiteralIntVector`, a raw `long[]` build). Since
todo-319 the reader is not the only producer: `PureBuiltinFolder` reduces a
literal `(coerce '(...) '(vector (unsigned-byte N)))` / `(make-array ...
:initial-contents '(...))` to the same value, which is how a library's own
constant tables reach these emitters.

### A long literal is DATA, not a run of `array.set`s

**A packed literal of 16 elements or more goes into the module's static data at
the element width, and the site becomes a copy loop over it: `w/8` bytes an
element plus a fixed ~45.** Below that threshold the old emission stands
(`array.new_default` + one `array.set` per non-zero element, ~12-17 bytes each),
so every hand-written short literal compiles to the bytes it always did. The
crossover is really around 7 elements; 16 buys margin for exactly that reason.
Measured marginal cost at the three widths: 4.0 / 2.0 / 1.0 bytes an element,
i.e. the element width and nothing else (`WasmLispCompilerTest.aLiteralLookupTable
CostsItsOwnBytesAndNotThreeTimesThem`).

Three things this emission is careful about, each of which is a trap if changed:

- **The bytes go through `StringTable.appendShakeableBlob`**, i.e. into the ONE
  active data segment the string data already occupies (4-byte aligned), and the
  blob is registered as a `DroppableDataRange`. `--optimize` therefore cuts a
  dead table's bytes exactly as it cuts a dead string's — a 100-element table in
  an unreferenced defun leaves 8 bytes of alignment padding behind and nothing
  else. This is also why it is NOT `array.new_data`: that instruction needs a
  PASSIVE segment and a datacount section, and `WasmTreeShaker` neither parses
  passive segments nor renumbers a `dataidx` when it drops one — it throws on
  the opcode on purpose (`scanGc`). Widening the shaker is the prerequisite for
  ever using it, and it would buy ~35 bytes a table.
- **The segment base is an explicit `i32.const`, not the load's memarg offset.**
  The shaker's droppable-range probe reads `i32.const` values out of surviving
  bodies and skips memargs, so a base hidden in a memarg would let it cut a
  table whose own reader is still alive.
- **The loop counter is an i64 scratch local, so an ASYNC resume body falls back
  to the `array.set` run.** That body assembles its own locals declaration and
  never resolves an i64 placeholder, which is why fusion, the raw `let` locals
  and the counted `dotimes` all stand down there too.

The array is still allocated and filled at the SITE, so each evaluation yields a
fresh, independently mutable vector — the property `PureBuiltinFolder` rests on
when it bakes a table (`.kb/pure-builtin-fold.md`).

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
  An asyncMode `rontolisp:async-defun` is excluded from the collection even
  when its rewritten plain defun qualifies textually: a call must build the
  `TYPE_FUTURE` its entry+resume state machine answers, and splicing the raw
  body handed a synchronous caller the value instead (`.kb/async-await.md`).

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
