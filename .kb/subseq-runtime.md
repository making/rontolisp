# `subseq` and general-array element access are shared callees, not per-site code

**Invariant: no `subseq` site emits the array copy loop inline, and no `aref` / `%aset` /
`row-major-aref` / `%row-major-aset` site emits the displacement-chain walk inline. The program
carries each once.** Same lesson as `.kb/string-write-runtime.md`, `.kb/wasm-shared-coercion.md`.
The STRING lane answers a MUTABLE CHARACTER VECTOR (`_subseqCv` JVM, `_subseq_str` /
`FUNC_SUBSEQ_STR` wasm) -- `.kb/string-write-runtime.md`.

## `%subseq-runtime` -- a spliced defun, both compile paths
- `LispMacroExpander.expandSubseqCompat` dispatches on runtime type: string or cons ->
  `%subseq-core` (what the per-backend `subseq` compilers emit); a general array is copied
  into a fresh `%array-alike` -- by `(%replace-bulk out seq 0 start n)` first (wasm: one
  `array.copy` when both are packed integer vectors of one width, `.kb/sequence-op-runtimes.md`),
  element-wise when that declines. Without the bulk arm a 64 KiB octet `subseq` cost ~11 M fuel
  (~1.4 ms native), and it is what every fetched body chunk goes through (`.kb/fetch-http.md`,
  "Throughput"); pin: `WasmLispCompilerIntegrationTest.subseqOfAPackedIntegerVectorIsOneBulkCopy`
  (a wasmtime fuel budget). `subseqRuntimeWrapper()` is the callee; `end` is a
  PARAMETER, nil when omitted, so one call shape serves 2- and 3-arg calls.
- **Injection is the BACKEND's**, in the same loop that adds the `BuiltinFunctionWrappers`
  (`JvmLispCompiler` / `WasmLispCompiler`), not `expandTopLevelDefinitions`: most `subseq` sites
  live in wrapper bodies, which do not exist until the backend generates them.
- Gates -- wasm: any `subseq` call. JVM: that AND `programUsesAnyArrayOp`, because the copy arm
  names `aref`/`%aset` and would pull ~120 KB of array runtime into a string-only program.
  `subseq`/`copy-seq`/`replace` raise that gate themselves (~9.5 KB on a minimal program).
- A site routes to the helper only when `%subseq-runtime` really is among the program's functions,
  inlining otherwise -- an under-predicting gate costs sharing, not correctness.
- `LispMacroExpander.programUsesGeneralArrayOp` is the ONE list of "this program can hold an
  array"; `JvmLispCompiler.programUsesAnyArrayOp` is that list plus its `concatenate` term.
- `%schar-set-runtime` stays spelled `%subseq-core`, not `%subseq-runtime`.

## `%array-alike` -- the copy is the SAME KIND as the source
**Invariant: `(%array-alike seq n)` answers a fresh zero-filled rank-1 array whose element
type is `(array-element-type seq)`, on all four backends, whichever representation `seq`
is in** -- a packed integer vector, a packed float array (every width the backend has), a
fill-pointer / adjustable vector that only REMEMBERS a packed width, or a displaced view
whose chain ends on any of those. Keyed on the ELEMENT TYPE, never on the runtime class:
a class test per representation is exactly what let the adjustable and float shapes fall
through to a general vector on the two compilers until 2026-09-06 (`.todo/719`), while a
`(unsigned-byte 8)` `long[]` alone was recognized.
- Interpreter: `Environment`'s `%array-alike` (`packedCopyForElementType`).
- JVM: ONE helper, `_arrayAlike` in the general array group (`JvmArrayRuntimeBuilder`),
  always emitted with the group: it calls `_arrayElementType` (which already hops a
  displaced chain and reads a packed target's width) and switches on the VALUE -- the
  `(unsigned-byte w)` cons, the three float width names via `JvmPackedFloatWidth` (the
  one owner of the packed header layout), else `_arrayMake`. There is no `_ivAlike` /
  `_fvAlike` tier and no per-gate routing at the site; the produced representation can
  only be one the program's gates already emit accessors for, since the element type
  came from a `make-array` those same scans saw.
- wasm: `WasmArrayCompiler.compileArrayAlike`, inline at the one site (the
  `%subseq-runtime` defun). `emitAlikeKey` resolves the KEY first -- a packed value is
  its own; a general array is walked to its chain end, a packed end becoming the key and
  a buckets-backed end leaving its meta MARKER word -- then one dispatch: the key's type
  for the packed families (a farray's width is its data type, under `--simd` the vblock's
  kind word), the marker for the remembered widths (arms gated on `Ctx.typedArrayCodes`
  like `emitRememberedElementType`'s), the general vector last.
- A character element type is the general vector on every backend: a rank-1 character
  array is a string and `subseq`'s `stringp` arm answers for it before `%array-alike`.
- Pins: `JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest`
  `compileSubseqOfAPackedFloatArrayKeepsTheWidth` +
  `compileSubseqOfAnAdjustablePackedVectorKeepsTheWidth` (the wasm pair runs `--simd`
  too), ci-spec `subseq-of-an-adjustable-packed-vector`.

## `_arr_get` / `_arr_set` -- wasm runtime functions
- A general array's field 0 is the header cons `(dims . (meta . data))`; a cell `data` means a
  DISPLACED VIEW, so a read adds the view offset and continues at the target's header, repeatedly.
  `WasmArrayRuntimeBuilder` holds that walk once per module for all five accessor sites.
- The packed float and packed integer arms deliberately STAY inline (the integer one is the fused
  raw-`i64` store, `.kb/packed-integer-vectors.md`).
- Indices: `FUNC_ARR_GET`/`FUNC_ARR_SET` appended after `FUNC_AS_F64` (new `FX_FUNC_LAST`),
  `TYPE_ARR_SET` after `TYPE_UB_READ` (new `IARR_TYPE_LAST`); `_arr_get` reuses `TYPE_BIG_SHIFT`.
  Appended, so existing `FUNC_*`/`TYPE_*` values are unchanged.
- If these become hot, add a fast path at the SITE, not a return to inlining the walk.

## Bounds check -- the STRING lane only (todo a42)
**Invariant: `0 <= start <= end <= (length seq)` for the string lane, checked before any
byte/element copy, on every backend, with the SAME text.** Before a42 only the
interpreter (`Environment`'s `SUBSEQ` arm) checked this and raised
`"SUBSEQ: invalid bounds S, E for string of length N"`; the JVM's `_subseqCv` (the
`scStr`/immutable-string arm `JvmArrayRuntimeBuilder` builds) let a bad range fall
straight into `String#substring`, surfacing a raw `StringIndexOutOfBoundsException`
instead, and wasm's `_subseq` (`WasmStringRuntimeBuilder.buildSubseqBody`'s STRING branch)
had no check at all -- an unsigned loop bound (`I32_GE_U`) meant `start > end` silently
answered `""` and an over-large `end` walked past the content into whatever bytes
followed.
- JVM: `_subseqCv`'s `scStr` arm calls `_scount(s)` (`JvmStringIndexRuntimeBuilder`, always
  emitted) for the character length, resolves the omitted-`end` sentinel, and on a
  violation builds `new RuntimeException(...)` from chained `String.valueOf(int)` +
  `concat` (the `_oob`/`emitRankCheckAndReturn` idiom, `.kb/error-handling.md`) --
  a plain, condition-less `RuntimeException` is exactly what the interpreter's
  condition-less `LispEvalException` synthesizes too: both land as `simple-error` under
  `handler-case`.
- wasm: `_subseq`'s STRING branch calls `FUNC_SEQ_LEN` (the shared `length` dispatch,
  `.kb/length-runtime.md`) for the character length, before translating the character
  indices to byte offsets. In EH mode a violation boxes each of `start`/`end`/`len` as an
  i31 and renders it through `FUNC_PRIN1_TO_STR` (the `WasmOperandTypes.pushBound` trick,
  no itoa duplicated), concatenates around three literal pieces interned as
  `WasmLispCompiler.StringTable.StringEntry`s, and throws `(nil . message)` on
  `$lisp-cond` like `WasmErrorCompiler.emitThrowPayload`. Outside EH mode: a bare
  `unreachable`, like every other unchecked failure that backend takes when no tag exists
  to throw on.
- **The three literal pieces MUST be interned before `WasmLispCompiler.compile` calls
  `stringTable.toByteArray()`** (same requirement as `WasmOperandTypes.Texts`, both
  interned right next to each other) -- interning them lazily inside
  `buildSubseqBody` (called later, in the `.addFunction(...)` chain that assembles the
  fixed runtime bodies) records the right offset/length bookkeeping but the bytes
  never make it into the data segment: the printed message comes back with the right
  SHAPE (numbers in the right positions) and blank ASCII spaces where the literal text
  should be -- the heap starts right where those bytes should have lived and overwrites
  them. Caught by actually running the compiled test, not by reading the bytecode.
- The list lane is UNCHECKED on both compilers still -- an over-large `end` silently
  truncates, matching pre-a42 behavior; not part of this fix (the interpreter's own list
  arm is unaffected, since it was already correct). The `--no-gc` scalar wasm backend
  (`NoGcWasmCompiler`, a fourth variant outside `CiSpecE2eTest`'s four) is untouched too --
  `.kb/no-gc-scalar-wasm.md` already names its `subseq` as unchecked, and that measurement
  stands.
- Pins: `SubseqBoundsFixture` (`LispEvaluatorTest#subseqSignalsInvalidBoundsOnEveryBackend`,
  `JvmLispCompilerTest#compileAndRunSubseqSignalsInvalidBounds`,
  `WasmLispCompilerIntegrationTest#subseqSignalsInvalidBounds`).

## Tests
- `LispMacroExpanderTest.aSubseqSiteIsOneCallWhenTheProgramCarriesTheSharedDispatch`,
  `.theSharedSubseqDispatchAnswersTheSameThingAsTheInlinedOne` (the helper must not call `subseq`
  itself), `.theGeneralArrayGateNamesTheOperatorsThatCanProduceOne`.
- `WasmLispCompilerTest.anElementAccessSiteDoesNotCarryItsOwnCopyOfTheSharedRuntime` -- the byte
  budget; nothing else notices, every arrangement compiles and runs correctly.
- Displaced-array and fill-pointer cases in `WasmLispCompilerIntegrationTest`,
  `JvmLispCompilerTest`, `ci-spec.yaml`.
