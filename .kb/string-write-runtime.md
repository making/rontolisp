# The string arm of an indexed write is ONE function, not an inlined rebuild

**Invariant: no `(setf (aref v i) x)` / `(setf (elt v i) x)` / `(setf (char s i) c)` site emits the
string rebuild inline. Every one of them calls `%schar-set-runtime`, which the program carries at
most once.**

## The lowering
`LispMacroExpander.expandSetf` gives a rank-1 indexed place a runtime string arm (such a place may
hold a string, and CL says `(setf (aref s i) c)` on one is legal -- `.kb/adjustable-arrays.md`).
Four place heads reach it -- `aref`/`svref`, `elt`, `char`/`schar`, `row-major-aref` -- funnelling
into `%schar-set`, expanded by `expandScharSetFunctional`:

```lisp
(let ((__schar_i i)) (let ((__schar_c c))
  (setq v (%schar-set-runtime v __schar_i __schar_c)) __schar_c))
```

- `scharSetRuntimeDefun()` answers **the string the write leaves behind** -- the same object for a
  mutable character vector, a fresh one for an immutable string; one call-site shape serves both.
  The `setq` is why the place must be a VARIABLE (lite semantics).
- **The rebuild uses `%subseq-core`, not `subseq`**: it runs only where `%arrayp` said no, so
  `expandSubseqCompat`'s general-array copy arm is dead there. 7,187-byte helper vs **665**.
- **Injection**: `withScharSetRuntime` in `expandTopLevelDefinitions`, at both exits (the same two
  `withFormatRenderer` uses, `.kb/format.md`). It must scan the PRE-expansion program, so it names
  the PLACE HEADS (`aref`/`svref`/`elt`/`char`/`schar`/`row-major-aref`/`%schar-set`, any
  position). Deliberately generous: over-injecting costs one defun `--optimize` drops,
  under-injecting is a call to a function that does not exist.
- The interpreter never runs `expandTopLevelDefinitions`; its `%schar-set` is a real in-place
  primitive.

**Why a function**: the rebuild is two `subseq`s, a `string` and two `%string-concat`s, and
`subseq` lowered to an inline copy LOOP -- ~8 KB of wasm at every site, paid even by an array-only
program, because nothing in `(setf (aref m i) 0.0)` says `m` is not a string. One wasm-GC site
8,615 -> 588 bytes; one JVM `(setf (elt s i) v)` site 5,042 -> 293. **The crossover is one site**;
a program with no live site is unchanged (helper injected, then shaken out). What is left in a site
is `%aset` itself, whose GENERAL arm is a call too (`_arr_set`, `.kb/subseq-runtime.md`); the
packed arms stay inline on purpose. Same lesson as `.kb/wasm-shared-coercion.md`,
`.kb/format.md`'s `%fixed-decimal`.

## A string LITERAL is never written, on any backend
**Invariant: `(setf (char|schar|aref|elt s i) c)` where `s` holds a string LITERAL rebuilds the
string and rebinds the place on ALL FOUR backends; the source constant is untouched and
`(eq (f) (f))` on a literal stays `T`.** Where the place cannot be rebound the write is an ERROR on
all four -- at compile time on the three compile paths, at run time in the interpreter.

- The reader marks its own `LispString`s (`LispString.literal`, `LispReader`'s `StringToken` arm,
  read by `sourceLiteral()`), because that object IS the program text. Nothing else is marked --
  `copy-seq`/`concatenate`/`subseq`/`format` results and the rebuilt string are ordinary.
- Interpreter: `LispEvaluator.evalScharSet` (a `%SCHAR-SET` arm in `evalCons`, not a plain builtin
  call, because the callee cannot rebind its caller's variable) hands `Environment.scharSet` a
  rebind hook only when the place subform is a SYMBOL. `Environment.scharSet` owns the rule: bounds
  first, then the literal branch (`LispString.withCharAt` through the hook, else throw), then the
  in-place write. `%schar-set` as a first-class value has no hook and refuses a literal.
- **Limits, not silent**: the place must be a VARIABLE, and the update is invisible through an
  alias taken before the write (`.kb/asdf.md`, cl-base64 item 3).

### The BULK writes
**Invariant: a DESTRUCTIVE BULK operation whose target is a string literal -- `replace`, `fill`,
and the `(setf (subseq s start end) v)` that lowers to `replace` -- lands on a FRESH COPY on all
four backends. The modified string reaches the program only as the RETURN VALUE.**

- Those are FUNCTION calls, not place forms, and `(setf (subseq ...))` hoists its sequence subform
  into a temporary, so the return value is the only channel -- already what the three compile paths
  use. Only the INTERPRETER moved: `Environment`'s `replace`/`fill` string arms copy through
  `LispString.copyForBulkWrite` when `sourceLiteral()` says the target is program text.
- Only those three ever diverged (the ones reaching `replaceInPlace`/`setCharAt`).
  `nstring-upcase`, `nreverse`, `nsubstitute`, `map-into`, `sort` were already functional there.
- `%aset`/`%row-major-aset` called DIRECTLY (no rebind hook) REFUSE a literal rather than copy.
- `row-major-aref` is on the routed list, so a VARIABLE place gets the same string arm; a
  NON-variable place falls through to `%row-major-aset`, which still cannot take a string. Its READ
  arm shares `aref`'s: `charRef` in the interpreter, `emitAref1FromSlots` on both wasm-GC backends,
  the rank-1 helper on the JVM.
- A `#P"..."`/`#S(...)` literal is `eq` to itself on all four (`.kb/quoted-data.md`). A string
  nested inside an array literal is SHARED on all four even though the array is fresh per
  evaluation (`LiteralArrays.materialize` passes a non-array element through by identity).

## Allocated strings are mutable with identity
**Invariant: a string the program allocates through the producers below is a MUTABLE sequence with
identity on ALL FOUR backends, matching SBCL: aliases see each other's writes, a callee's write
reaches its caller, `replace`/`fill` write in place, and a displaced view writes THROUGH. A LITERAL
stays immutable.**

Producer set: `subseq` / `copy-seq`; `concatenate 'string`, `string-upcase` / `string-downcase` /
`string-capitalize`, literal-`nil` destination `format`, `with-output-to-string` /
`get-output-stream-string`, `read-line`; `string-trim` / `string-left-trim` / `string-right-trim`,
PROGRAM-WRITTEN `(map 'string ...)` and `(coerce seq 'string)`, `uiop:getenv`, the first-class
`#'concatenate` wrapper; `princ-to-string` / `prin1-to-string` / `write-to-string`.

### Mechanism
- **`subseq`/`copy-seq` flip in the PRODUCER** (`copy-seq` is `(subseq seq 0)` everywhere): the
  string lane of `%subseq-core` answers a fresh mutable CHARACTER VECTOR. JVM: `JvmSubseqCompiler`'s
  string arm is one call to `_subseqCv(Object, int, int)` (`JvmArrayRuntimeBuilder`) -- a charvec or
  string-view input copies through `_rmGet` without rendering the source (chained slicing stays
  linear), an immutable `String` slices by code point and converts once through `_strToCharVec`
  (fill-pointer slot cleared: a subseq result is SIMPLE). WASM: `_subseq_str`
  (`FUNC_SUBSEQ_STR`) -- `_charvec_p` -> `_arr_get` copy, else byte-level `_subseq` converted once
  through `_str_to_cv`. `subseq`/`copy-seq`/`replace` joined `programUsesAnyArrayOp`. Interpreter
  unchanged.
- **Every other producer is a per-SITE wrap**, not a per-primitive rewrite: the `Jvm`/`WasmExprCompiler`
  case compiles the producer as before and finishes with ONE call, `_toMutStr(Object)` /
  `_to_mut_str` (`FUNC_TO_MUT_STR`). It converts a QUOTE-FRAMED string once (fill pointer cleared)
  and passes everything else through. **The frame test is load-bearing**: a SYMBOL shares the string
  representation BARE, and `read-line`'s eof-value or a symbol flowing out of a wrapped expression
  must not be laundered -- unframed, the conversion would even trip the UTF-8 decode.
- **One gate for both backends**: `compiler/MutableStringProducers.programUsesAny`, a pre-expansion
  source scan for the producer names (plus the `(format nil ...)` shape, the `%read-line-raw`
  component alias, `%host-getenv`, and by SHAPE `isMapToString` / `isCoerceToString` with a LITERAL
  `'string` designator), computed identically by both compilers and copied into async chunk
  contexts by `WasmAsyncEmit`. On the JVM it also joined `programUsesAnyArrayOp`.
- `%str-fresh` is the Lisp-level spelling of the wrap (`PureBuiltinFolder`'s fresh-string constants,
  the `coerce` build arm, `concatenateWrapper`'s `%string-concat` reduce); the interpreter binds it
  as a copy.
- **`(coerce x 'string)` over a STRING answers the ARGUMENT** (CLHS; `%seq-string` depends on it),
  so the wrap sits on the BUILD arm only: `(if (stringp x) x (%str-fresh <build>))`.
- **A sequence operator's own result conversion is NOT a program-written `coerce`.** `reverse` /
  `remove` / `remove-if` / `remove-duplicates` / `substitute` / `substitute-if` / `sort` lower
  through `seqResultDispatchForm`, whose generated conversions carry the INTERNAL designator
  `LispNames.SEQ_STRING_RESULT` (`%seq-string-result`) -- reads as `string` everywhere a result
  type is inspected and skips exactly the wrap.
- **The print family flips only the PUBLIC names.** `%princ-piece` / `%prin1-piece`
  (`PRINC_PIECE_INTERNAL` / `PRIN1_PIECE_INTERNAL`) are the public conversions minus the wrap, and
  every piece the codegen's expansions build stays an immutable internal value.
  `expandPrintObjectHook` rewrites the piece names exactly as the public ones, so a piece rendering
  a user instance still consults the method; the raw `%princ-to-string`/`%prin1-to-string` could
  NOT serve -- they are print-object-FREE by design (`.kb/clos.md`). Expander sites and the
  library `print-object` methods all use the piece names.
- `StringValuedForms.ALWAYS_STRING` holds the PIECE names, not the two public print names (a public
  name can now answer a character vector, and an entry that does would silently drop a
  normalization). `%fixed-decimal` / `%string-concat` stay.
- `PureBuiltinFolder` folds a literal-argument `string-upcase`/`string-downcase`/
  `(concatenate 'string ...)`/`subseq` and the three public print names to a `(%str-fresh "...")`
  constant; the piece names and `symbol-name` fold to a plain literal. Both print folds are blocked
  under `*print-case*` (`.kb/pure-builtin-fold.md`).

### Traps
- **Byte-oriented library accumulators must NOT go through the wrapped `concatenate`.**
  sockets.lisp's and stdin.lisp's accumulators build strings whose bytes ARE wire bytes (partial
  UTF-8, or raw non-UTF-8); the wrap's conversion traps on an invalid lead byte and re-encodes raw
  bytes >= 0x80. Those sites append through `%string-concat` directly, with a comment at each.
  `%string-concat` itself stays UNWRAPPED: it is the internal append the codegen builds with.
- **`read-line`'s eof-value comes back by IDENTITY.** `(read-line s nil eof)` lowers to
  `(or (read-line s) eof)`; the wrap must be compiled from the `read-line` case and land on the
  inner one-argument call only, or a STRING sentinel comes back as a mutable COPY -- visible on the
  WASM backends only (`eq` is object identity there).
- `%json-pairs` merging through the WRAPPED concatenate re-converted every intermediate (JVM
  json-stringify 116 -> 245 ms); it uses `%string-concat`, and `%json-stringify` hands its one
  caller-visible result through `copy-seq`.

### Boundary chokepoints: render a character vector exactly once
Every place that reads a string's BYTES or hands one outside the Lisp value model normalizes at
entry.
- WASM: `_str_to_mem`, `_write_line` / `_write_stream_str` (returning the ORIGINAL argument, so
  identity survives), `emitStageStringParam`, `%str-byte-length` / `%str-byte-ref`.
- JVM IO/streams: `JvmIoRuntimeBuilder` mints `_strv` (only under the array gate) before every
  `(String)` cast -- `emitStripQuotes`, `_open`, `_probeFile`, `_listDirectory`, `_writeLine`, the
  socket write, `_makeStringInputStream`; also `JvmLoadCompiler`,
  `JvmSocketRuntimeBuilder.emitStripQuotes`, `JvmFetchRuntimeBuilder` (threaded `strvRef`),
  `JvmErrorCompiler.compileThrowRuntimeException`, `JvmWarnCompiler`.
- `ffi:` / `objc:` / `java:` -- `JvmFfiTemplate.lispString`, `JvmObjcTemplate.lispString`,
  `JavaBridgeTemplate.lispString` AND `marshal` (every ARGUMENT position). The ffi/objc hooks bind
  `_strv` REFLECTIVELY (`strvMethod`, looked up beside `_apply` in `bind`; absent exactly when the
  program has no array runtime, which is exactly when no charvec can exist), because `_strv` is the
  single authority on the representation and the template travels.
- `uiop:getenv` (`JvmGetenvCompiler`, `WasmGetenvCompiler`); `symbol-name`/`intern`/`make-symbol`
  (`JvmSymbolApiCompiler`, the WASM intern funnel).
- The `equalp` hash-key fold -- the key renders BEFORE the fold (`JvmHashRuntimeBuilder._hashKey`
  ahead of the travelling `RontoHashTable.equalpKey`, which deliberately does not know the array
  representation; `WasmEqualpKeyRuntimeBuilder` at the fold's entry).
- jvm-export / Maven plugin / servlet: `_exStr` renders before its frame check. The HTTP transport
  renders in Lisp for all backends (`%http-header-name` / `%http-header-value` /
  `%http-join-strings`), because `RontoHttpClack.toResponse`'s `instanceof String` guards silently
  DROPPED charvec headers and bodies.
- GPU (`am.ik.gpu`) CANNOT receive a Lisp string -- numeric seam end to end.
- Canaries: `examples/jvm/cffi-sqlite.lisp`, `examples/macos/system-frameworks.lisp`.

### What is STILL immutable
- `reverse` / `remove` / `remove-if` / `remove-if-not` / `remove-duplicates` / `substitute` /
  `substitute-if` / `sort` over a string -- the gate cannot see whether the sequence is a string,
  so a program reversing a LIST would pay the JVM array runtime (+37-48% of class). **Trigger**: a
  JVM shape where a charvec can exist without the general-array runtime, or a gate that can tell a
  string sequence from a list one.
- A COMPUTED `format` destination that is nil at run time, and a COMPUTED `(coerce x ty)` naming a
  string at run time: the wrap would be CORRECT, the GATE costs +2.4-3.3 KB of class in every
  program that formats to a stream. `expandComputedCoerce` routes to the unwrapped shared
  `%seq-to-string` -- the one line to change.
- `symbol-name` / `(string 'sym)` / `gensym` / `make-symbol` names (CLHS leaves mutation undefined;
  the INTERPRETER mutates the symbol's own name through such a write -- a separate wart).
- fetch / socket / gray-stream read results, `%io-read-line`'s socket arm included; only
  `%read-line-raw` wraps.
- json-parse's multi-fragment string values (`%json-concat` merges through the unwrapped
  `%string-concat`). If value identity matters, wrap in `%json-string`'s return, not in the merge.
- Every string PIECE built with `%princ-piece` / `%prin1-piece`.

### Cost shape
Per flipped producer call the result is a boxed charvec instead of a `String`/substring.
Whole-string consumers (`string=`, concatenate, intern, hash, print) render a charvec once per
CALL -- bounded constant factors, nothing quadratic, because index sites read elements
(`.kb/string-index-cost.md`) and `subseq`-of-charvec copies the slice. `position`/`find` no longer
coerce the whole sequence to a list per call (`buildPositionScan`, `.kb/seq-coerce-runtime.md`),
which is why tokenizer-shaped code got ~35x faster with the flip.

### Unfinished
If `%schar-set-runtime` becomes hot (one call per character written in `make-string` fill loops),
the answer is a fast path at the SITE -- a `ref.test` on the mutable-vector representation before
the call -- NOT a return to inlining the rebuild.

## Pinning tests
- `LispMacroExpanderTest.aStringWriteSiteIsOneCallAndNotAnInlinedSubseqConcatRebuild` (the site
  names `%SCHAR-SET-RUNTIME` and none of `SUBSEQ`/`%STRING-CONCAT`/`%ARRAYP`),
  `.theStringWriteRuntimeIsInjectedForAnArrayPlaceAndOmittedWithoutOne`,
  `.anExpanderBuiltStringPieceIsTheInternalConversionNotThePublicProducer` (the spelling guard).
- Indexed write: ci-spec `setf-elt-cross-backend`;
  `LispEvaluatorTest.evalSetfEltDispatchesOverListStringAndVector`,
  `JvmLispCompilerTest.compileSetfEltOnAStringMutatesIt`,
  `WasmLispCompilerIntegrationTest.compileSetfEltDispatchesOverListStringAndVector`.
- LITERAL rule: ci-spec `string-literal-write-cross-backend`;
  `LispEvaluatorTest.aStringLiteralIsSharedAcrossEvaluationsOnEveryBackend`,
  `#aWriteThroughAStringLiteralRebindsThePlaceAndLeavesTheConstant`,
  `#aWriteThroughAStringLiteralWithNoVariablePlaceIsAnError`,
  `#aWriteThroughAnAllocatedStringBufferIsStillInPlace`.
- BULK rule: ci-spec `string-literal-bulk-write-cross-backend`;
  `LispEvaluatorTest.aBulkWriteThroughAStringLiteralLandsOnACopyAndLeavesTheConstant`,
  `#aBulkWriteThroughAnAllocatedStringBufferIsStillInPlace`,
  `#rowMajorAsetOnAStringLiteralAsAFirstClassCallIsStillAnError`.
- `row-major-aref` place: ci-spec `row-major-aref-string-cross-backend`;
  `LispEvaluatorTest.rowMajorArefReadsAStringLikeAref` and the two compiler twins.
- Mutable identity: ci-spec `string-identity-cross-backend`;
  `LispEvaluatorTest.aFlippedStringProducerResultHasWritableIdentity`, and in both compiler tests
  `.compileAFlippedStringProducerResultHasWritableIdentity`,
  `.compileDisplacedStringViewOverACopySeqResultWritesThrough`,
  `.compileAPrintObjectRoutedPrincToStringResultHasWritableIdentity`,
  `.compileAProducerBuiltStringFoldsAsAnEqualpHashKey`, plus
  `JvmLispCompilerTest.compileALiteralArgumentProducerCallIsNotFoldedIntoASharedLiteral`.
- Boundaries: `JvmFfiInteropCompilerTest.aProducerBuiltStringCrossesTheFfiBoundary`,
  `JvmObjcInteropCompilerTest.aProducerBuiltStringCrossesTheObjcBoundary`, the producer rows in
  `JvmJavaInteropCompilerTest.staticCall`.
