# The string arm of an indexed write is ONE function, not an inlined rebuild

**Invariant: no `(setf (aref v i) x)` / `(setf (elt v i) x)` / `(setf (char s i) c)`
site emits the string rebuild inline. Every one of them calls `%schar-set-runtime`,
which the program carries at most once.**

## The lowering

- `LispMacroExpander.expandSetf` gives a rank-1 indexed place a runtime string arm
  (a rank-1 array place may hold a string, and CL says `(setf (aref s i) c)` on one is
  legal -- `.kb/adjustable-arrays.md`).
- Four place heads reach it -- `aref`/`svref`, `elt`, `char`/`schar`, `row-major-aref` --
  and all funnel into `%schar-set`, which the compile paths expand with
  `expandScharSetFunctional`:

```lisp
(let ((__schar_i i)) (let ((__schar_c c))
  (setq v (%schar-set-runtime v __schar_i __schar_c)) __schar_c))
```

- `scharSetRuntimeDefun()` is the callee; it answers **the string the write leaves
  behind** -- the same object for a mutable character vector (written in place through
  `%row-major-aset`), a fresh one for an immutable string. One call-site shape serves
  both arms. The `setq` is why the place must be a VARIABLE (lite semantics,
  `.kb/adjustable-arrays.md`).
- **The rebuild uses `%subseq-core`, not `subseq`.** It runs only where `%arrayp` said
  no, so `expandSubseqCompat`'s general-array copy arm (`%array-alike` + inline
  `dotimes`) is dead there. 7,187-byte helper vs **665**-byte one.
- **Injection**: `withScharSetRuntime` in `expandTopLevelDefinitions`, at both of its
  exits (the same two `withFormatRenderer` uses, `.kb/format.md`). It must be a scan of
  the PRE-expansion program (expression expansion is per-form and cannot add a top-level
  defun), so it names the PLACE HEADS (`aref`/`svref`/`elt`/`char`/`schar`/
  `row-major-aref`/`%schar-set`, any position). Deliberately generous: over-injecting
  costs one unreachable defun `--optimize` drops; under-injecting is a call to a
  function that does not exist.
- The interpreter never runs `expandTopLevelDefinitions`; its `%schar-set` is a real
  in-place primitive.

## A string LITERAL is never written, on any backend

**Invariant: `(setf (char|schar|aref|elt s i) c)` where `s` holds a string LITERAL
rebuilds the string and rebinds the place on ALL FOUR backends; the source constant is
untouched and `(eq (f) (f))` on a literal stays `T`.** Where the place cannot be rebound
the write is an ERROR on all four -- refused at compile time by the three compile paths,
at run time by the interpreter.

- The reader marks its own `LispString`s (`LispString.literal`, `LispReader`'s
  `StringToken` arm; `LispString.sourceLiteral()` reads the mark), because that object IS
  the program text. Nothing else is marked -- `copy-seq`/`concatenate`/`subseq`/`format`
  results, and the rebuilt string, are ordinary.
- Interpreter: `LispEvaluator.evalScharSet` (a `%SCHAR-SET` arm in `evalCons`, not a
  plain builtin call, because the callee cannot rebind its caller's variable) evaluates
  the three subforms once, then hands `Environment.scharSet` a rebind hook when -- and
  only when -- the place subform is a SYMBOL. `Environment.scharSet` owns the rule:
  bounds first, then the literal branch (rebuild via `LispString.withCharAt` through the
  hook, or throw when there is no hook), then the in-place write.
- `%schar-set` as a first-class function value has no hook, so it refuses a literal.
- **Limits, not silent**: the place must be a VARIABLE, and the update is invisible
  through an alias taken before the write (`.kb/asdf.md`, cl-base64 item 3).

### The BULK writes

**Invariant: a DESTRUCTIVE BULK operation whose target is a string literal --
`replace`, `fill`, and the `(setf (subseq s start end) v)` that lowers to `replace` --
lands on a FRESH COPY on all four backends. The modified string reaches the program only
as the operation's RETURN VALUE; the variable is not rebound and the source constant is
untouched.**

- `replace`/`fill` are FUNCTION calls, not place forms, and `(setf (subseq ...))` hoists
  its sequence subform into a temporary, so the return value is the only channel -- which
  is already what all three compile paths use. Only the INTERPRETER moved:
  `Environment`'s `replace`/`fill` string arms copy through `LispString.copyForBulkWrite`
  when `sourceLiteral()` says the target is program text.
- Only those three of the family ever diverged (the ones reaching `LispString`'s in-place
  `replaceInPlace`/`setCharAt`). `nstring-upcase`, `nreverse`, `nsubstitute`,
  `map-into`, `sort` were already functional on the interpreter.
- `%aset`/`%row-major-aset` called DIRECTLY (not through a `setf` place, so no rebind
  hook) REFUSE a literal rather than copy -- `%schar-set`'s first-class-value case.
- `row-major-aref` is on the routed list, so `(setf (row-major-aref s i) c)` for a
  VARIABLE place is the same string arm `aref`/`svref`/`elt` have (in place for a mutable
  character vector, rebind for a literal, error for an unrebindable place). A
  NON-variable place falls through to `%row-major-aset`, which still cannot take a string
  -- the `(setf (aref s i) c)` spelling has the identical restriction.
- `row-major-aref`'s READ arm: the interpreter's `ROW-MAJOR-AREF` (`Environment`) uses
  the same `charRef` call `AREF` makes; both wasm-GC backends' `compileRowMajorAref`
  calls the same `emitAref1FromSlots` slot dispatch `compileAref`'s rank-1 fallback uses
  (which has the string arm) instead of repeating the three-way chain. The JVM's already
  shared `aref`'s rank-1 helper.
- A `#P"..."`/`#S(...)` literal is `eq` to itself on all four: both compile backends
  memoize a bare instance literal into the lazy slot a quoted datum uses
  (`.kb/quoted-data.md`).
- A string nested inside an array literal is SHARED on all four (`#("abc")`) even though
  the array around it is fresh per evaluation -- `LiteralArrays.materialize` passes a
  non-array element through by identity. Writing through it obeys the rule above.

## Allocated strings are mutable with identity

**Invariant: a string the program allocates through the producers below is a MUTABLE
sequence with identity on ALL FOUR backends, matching SBCL: aliases see each other's
writes, a callee's write to a string argument reaches its caller, `replace`/`fill` write
in place, and a displaced view over such a string writes THROUGH to it. A LITERAL stays
immutable and the literal rules above are unchanged.**

Producer set (flipped in four rounds): `subseq` / `copy-seq`; `concatenate 'string`,
`string-upcase` / `string-downcase` / `string-capitalize`, literal-`nil` destination
`format`, `with-output-to-string` / `get-output-stream-string`, `read-line`;
`string-trim` / `string-left-trim` / `string-right-trim`, PROGRAM-WRITTEN
`(map 'string ...)` and `(coerce seq 'string)`, `uiop:getenv`, the first-class
`#'concatenate` wrapper; `princ-to-string` / `prin1-to-string` / `write-to-string`
(a `print-object`-routed rendering included).

### Mechanism

- **`subseq`/`copy-seq` flip in the PRODUCER** (`copy-seq` is `(subseq seq 0)`
  everywhere, so one lane covers both): the string lane of `%subseq-core` answers a
  fresh mutable CHARACTER VECTOR.
  - JVM: `JvmSubseqCompiler`'s string arm (under the array gate) is one call to
    `_subseqCv(Object, int, int)` (`JvmArrayRuntimeBuilder`) -- a character-vector or
    string-view input copies `[start, end)` through `_rmGet`, never rendering the source
    (chained slicing stays linear); an immutable `String` slices by code point and
    converts once through `_strToCharVec` (fill-pointer slot cleared: a subseq result is
    a SIMPLE string). `subseq`/`copy-seq`/`replace` joined `programUsesAnyArrayOp`; a
    subseq-only program grew 3,908 -> 13,419 bytes, not the full ~120 KB array runtime.
  - WASM: `_subseq_str` (`FUNC_SUBSEQ_STR`, `WasmStringRuntimeBuilder.buildSubseqStrBody`)
    -- `_charvec_p` -> element copy through `_arr_get`, else the byte-level `_subseq`
    whose string result converts once through `_str_to_cv` (`FUNC_STR_TO_CV`); a
    cons-chain result passes through. +272 bytes on a subseq-carrying module.
  - Interpreter: unchanged, its strings were always mutable.
- **Every other producer is a per-SITE wrap**, not a per-primitive rewrite: the case in
  `Jvm`/`WasmExprCompiler` compiles the producer as before and finishes with ONE call --
  `_toMutStr(Object)` (`JvmArrayRuntimeBuilder`) / `_to_mut_str` (`FUNC_TO_MUT_STR`,
  `WasmStringRuntimeBuilder.buildToMutStrBody`). It converts a QUOTE-FRAMED string once
  through `_strToCharVec` / `_str_to_cv` (fill pointer cleared) and passes everything
  else through. **The frame test is load-bearing**: a SYMBOL shares the string
  representation BARE (a `java.lang.String` without quotes; a `TYPE_STRING` whose byte 0
  is not `0x22`), and `read-line`'s eof-value or a symbol flowing out of a wrapped
  expression must not be laundered -- unframed, the conversion would even trip the UTF-8
  decode.
- **One gate for both backends**: `compiler/MutableStringProducers.programUsesAny`, a
  pre-expansion source scan for the producer names (plus the `(format nil ...)` shape,
  the `%read-line-raw` component alias, `%host-getenv`, and by SHAPE `isMapToString` /
  `isCoerceToString` with a LITERAL `'string` designator), computed identically by
  `JvmLispCompiler` and `WasmLispCompiler` and copied into async chunk contexts by
  `WasmAsyncEmit`. On the JVM it also joined `programUsesAnyArrayOp`, so a wrap site
  always has the array runtime.
- `%str-fresh` is the Lisp-level spelling of the wrap. Callers: `PureBuiltinFolder`'s
  fresh-string constants, the `coerce` build arm, `BuiltinFunctionWrappers.
  concatenateWrapper`'s `%string-concat` reduce. The interpreter binds it as a copy
  (`Environment`).
- **`(coerce x 'string)` over a STRING answers the ARGUMENT** (CLHS; `%seq-string`,
  concatenate's per-argument normalizer, depends on it). The wrap sits on the BUILD arm
  only: `(if (stringp x) x (%str-fresh <build>))`.
- **A sequence operator's own result conversion is NOT a program-written `coerce`.**
  `reverse` / `remove` / `remove-if` / `remove-duplicates` / `substitute` /
  `substitute-if` / `sort` lower through `seqResultDispatchForm`, whose string arm is a
  literal `coerce` form. Those generated conversions carry the INTERNAL designator
  `LispNames.SEQ_STRING_RESULT` (`%seq-string-result`), which reads as `string`
  everywhere a result type is inspected and skips exactly the wrap.
- **`#'format` needed nothing**: its wrapper renders through `%fmt-render`, whose
  `%fmt-cat` is a `concatenate 'string`.
- **The print family flips only the PUBLIC names.** `%princ-piece` / `%prin1-piece`
  (`LispNames.PRINC_PIECE_INTERNAL` / `PRIN1_PIECE_INTERNAL`) are the public conversions
  minus the wrap, and every piece the codegen's own expansions build stays an immutable
  internal value. `expandPrintObjectHook` rewrites the piece names exactly as the public
  ones (`(%print-object-str x escape)`, or `%print-cased` under `*print-case*`), so a
  piece rendering a user instance still consults the method; the raw
  `%princ-to-string`/`%prin1-to-string` could NOT serve here -- they are
  print-object-FREE by design (`.kb/clos.md`). `Jvm`/`WasmExprCompiler` compile pieces
  through `compilePrintOperator` and stop; the three public names do the same and finish
  with the wrap. The interpreter binds the piece names as the same two functions
  (`Environment`) routed through `LispEvaluator.evalConsRareOperator`.
  Expander sites that moved to pieces: `opsToPieces`'s `~a`/`~s`/`~w`/`~d`/`~c`/`~f`,
  `decimalExpr`, `radixIntegerExpr`, the `~e` mantissa/exponent, `generalFloatExpr`,
  `fmtPadChar`, `printPiece`'s two-element shape test, `expandMap`'s STRING accumulator,
  `strictStringDesignatorForm`, the condition-message sites of the signal family,
  `typeNameOf`, `expandComputedGensym`; plus `format-render.lisp` (`%fmt-render`),
  json.lisp, url.lisp, sockets.lisp, stdin.lisp, http-server.lisp, gray.lisp's print
  dispatch, and the `print-object` methods of torch / geom / jzon.
- `StringValuedForms.ALWAYS_STRING` holds the PIECE names, not the two public print
  names (a public name can now answer a character vector, and an entry that does would
  silently drop a normalization a consumer needs). `%fixed-decimal` / `%string-concat`
  stay.
- `PureBuiltinFolder` folds a literal-argument `string-upcase` / `string-downcase` /
  `(concatenate 'string ...)` / `subseq` and the three public print names to a
  `(%str-fresh "...")` constant (compile-time value, per-evaluation fresh copy); the
  piece names and `symbol-name` fold to a plain literal. Both print folds are blocked
  under `*print-case*`. Mechanics and sizes: `.kb/pure-builtin-fold.md`.

### Traps

- **Byte-oriented library accumulators must NOT go through the wrapped `concatenate`.**
  sockets.lisp's and stdin.lisp's read-char/read-line/write-sequence accumulators build
  strings whose bytes ARE wire bytes (partial UTF-8, or raw non-UTF-8); the wrap's
  conversion traps on an invalid lead byte (a component `write-sequence` of
  `(vector 1 2 250 4)` hit "out of bounds array access" in the UTF-8 decode) and
  re-encodes raw bytes >= 0x80 otherwise. Those sites append through `%string-concat`
  directly, with a comment at each one. `%string-concat` itself stays UNWRAPPED: it is
  the internal append the codegen's expansions build with.
- **`read-line`'s eof-value comes back by IDENTITY.** `(read-line s nil eof)` lowers to
  `(or (read-line s) eof)`; the wrap must be compiled from the `read-line` case itself
  and land on the inner one-argument call only, or a STRING sentinel comes back as a
  mutable COPY -- visible on both WASM backends only (`eq` is object identity there; the
  interpreter and the JVM compare string CONTENT).
- `%json-pairs` merging through the WRAPPED concatenate re-converted every intermediate
  (JVM json-stringify 116 -> 245 ms); it uses `%string-concat`, and `%json-stringify`
  hands its one caller-visible result through `copy-seq`.

### Boundary chokepoints: render a character vector exactly once

A charvec flows everywhere a string can, so every place that reads a string's BYTES or
hands one outside the Lisp value model normalizes at entry:

- WASM: `_str_to_mem` (every host/linear crossing), `_write_line` / `_write_stream_str`
  (returning the ORIGINAL argument, so identity survives the write),
  `emitStageStringParam` (the component import string lowering), `%str-byte-length` /
  `%str-byte-ref` (socket accessors).
- JVM IO/streams: `JvmIoRuntimeBuilder` mints `_strv` (only under the array gate) before
  every `(String)` cast -- `emitStripQuotes`, `_open`, `_probeFile`, `_listDirectory`,
  `_writeLine`, the socket write, `_makeStringInputStream`; also `JvmLoadCompiler`
  (`load`'s path), `JvmSocketRuntimeBuilder.emitStripQuotes`, `JvmFetchRuntimeBuilder`
  (URL/method/header/body strips, threaded `strvRef`),
  `JvmErrorCompiler.compileThrowRuntimeException` and `JvmWarnCompiler` (a condition
  report lambda's `with-output-to-string` capture is a charvec).
- `ffi:` -- `JvmFfiTemplate.lispString` renders through a REFLECTIVELY BOUND `_strv`
  (`strvMethod`, looked up beside `_apply` in `bind`; absent exactly when the program has
  no array runtime, which is exactly when no character vector can exist). Covers
  `ffi:open`/`ffi:symbol` names, `:string` call arguments, `bufferBytes`. The reflective
  hook rather than an in-template renderer because `_strv` is the single authority on the
  representation (headers, fill pointers, displaced views) and the template travels.
- `objc:` -- `JvmObjcTemplate.lispString`, the identical hook (class names, selectors,
  `objc:string` content, `objc:data` buffers); the appkit/metal/scene libraries are Lisp
  over these verbs.
- `java:` -- `JavaBridgeTemplate.lispString` (class/method/field names) AND `marshal`
  (every ARGUMENT position: fixed arity, varargs, constructors).
- `uiop:getenv` -- `JvmGetenvCompiler` mints `_strv` before its `(String)` cast;
  `WasmGetenvCompiler` renders before `_getenv`'s `TYPE_STRING` read.
- The `equalp` hash-key fold -- the key renders BEFORE the fold
  (`JvmHashRuntimeBuilder`'s `_hashKey` before the travelling `RontoHashTable.equalpKey`,
  which deliberately does not know the array representation; `WasmEqualpKeyRuntimeBuilder`
  at the fold's entry). `equal` tables were already covered by `_hash`/`_equal`.
- jvm-export (and the Maven plugin / servlet entry points) -- `_exStr` renders before its
  frame check. The HTTP transport renders in Lisp for all backends:
  `%http-header-name` / `%http-header-value` / `%http-join-strings`, because
  `RontoHttpClack.toResponse`'s `instanceof String` guards silently DROPPED charvec
  headers and bodies.
- `symbol-name`/`intern`/`make-symbol` normalize at their compile sites
  (`JvmSymbolApiCompiler`, the WASM intern funnel).
- GPU (`am.ik.gpu` / the linalg kernels) CANNOT receive a Lisp string -- numeric seam end
  to end. No `Runtime.exec`-shaped calls exist in the emitted runtimes.

Canaries: `examples/jvm/cffi-sqlite.lisp`, `examples/macos/system-frameworks.lisp`.

### What is STILL immutable, and the trigger to revisit

- `reverse` / `remove` / `remove-if` / `remove-if-not` / `remove-duplicates` /
  `substitute` / `substitute-if` / `sort` over a string -- flipping them means putting
  their names in the gate, and the gate cannot see whether the sequence is a string, so a
  program that reverses a LIST would pay the JVM array runtime: `(print (reverse (list 1
  2 3)))` 14,674 -> 21,664 bytes of class (+6,990, +47.6%), `examples/console/nqueens`
  17,927 -> 24,662 (+6,735, +37.6%), against +164 bytes of wasm. The flat part is the
  array gate itself (hello_world +2,357, pi_approx +3,173, hanoi +3,011, contact-book
  +3,297 bytes of class; 0 bytes of wasm). **Trigger**: a JVM shape where a character
  vector can exist without the general-array runtime, or a gate that can tell a string
  sequence from a list one.
- A COMPUTED `format` destination that is nil at run time, and a COMPUTED
  `(coerce x ty)` whose `ty` names a string at run time. The wrap would be CORRECT for
  both (a non-string passes `_toMutStr` through); the GATE costs (+2.4 to +3.3 KB of
  class in every program that formats to a stream). `expandComputedCoerce` routes to the
  unwrapped shared `%seq-to-string` -- the one line to change if the gate question is
  answered.
- `symbol-name` / `(string 'sym)` / `gensym` / `make-symbol` names -- CLHS leaves
  symbol-name mutation undefined and SBCL shares the name object. (The INTERPRETER
  mutates the symbol's own name through such a write: a separate undefined-behavior wart.)
- fetch / socket / gray-stream read results, `%io-read-line`'s socket arm included; only
  the `%read-line-raw` fallback wraps.
- json-parse's multi-fragment string values (`%json-concat` merges through the unwrapped
  `%string-concat`; single-fragment values are subseq slices and already mutable). If
  value identity ever matters, wrap in `%json-string`'s return, not in the merge.
- Every string PIECE built with `%princ-piece` / `%prin1-piece`.

### Cost shape

Per flipped producer call the result is a boxed character vector instead of a `String` /
`substring`: ~7 us per 1,000-char `string-trim` or `princ-to-string` on both compiled
backends, ~0.4 us (JVM) / 0.14 us (WASM) for `princ-to-string` of an integer, ~1.5 us per
`concatenate` append. Whole-string consumers (`string=`, concatenate, intern, hash,
print) render a charvec once per CALL -- bounded constant factors, nothing quadratic,
because index sites read elements (`.kb/string-index-cost.md`) and `subseq`-of-charvec
copies the slice. The `(string x)` escape hatch and the remaining read lanes are tracked
outside this file. `position`/`find` no longer coerce the whole sequence to a list per
call (`buildPositionScan` walks a list through its cons cursor and a string/vector by
index -- `.kb/seq-coerce-runtime.md`), which is why tokenizer-shaped code got ~35x
faster rather than slower with the flip.
Sizes: the console corpus (hello_world, pi_approx, zlib, calc, contact-book, nqueens,
word-frequency, hanoi, error-handling) is byte-identical across the print round; a
program whose ONLY producer is `princ-to-string` joins the gate and pays the flat first-
producer cost -- `(defun f (x) (princ-to-string x))` 11,758 -> 12,110 bytes of wasm
(+352), 4,041 -> 7,837 of class (+3,796).

## Why it is a function

The rebuild is two `subseq`s, a `string` and two `%string-concat`s, and `subseq` lowered
to an inline copy LOOP on both compile paths -- **~8 KB of wasm at every site**, paid
even by an array-only program, because nothing in `(setf (aref m i) 0.0)` tells the
compiler `m` is not a string. One `(setf (aref m k) 1.0)` site on wasm-GC at
`--no-wasi --optimize`: **8,615 -> 588 bytes**; one `(setf (elt s i) v)` site on the JVM:
**5,042 -> 293 bytes**. `browser/webgl-cube` 218,235 -> 37,202 (-83.0%),
`webgl-platformer` -73.9%, `webgl-galaxy` -55.2%, `webgl-battlefront` -51.7%,
`webgl-robot-arm` -41.3%, `browser/hiragana` -2.4%.

**The crossover is one site**: a program with exactly one live site trades ~8 KB of
inline code for the ~665-byte function and comes out about even (`rainbow` +60 bytes). A
program with no live site is unchanged, the helper injected and then shaken out
(`heat3d` +2 bytes of index-width residue; `minesweeper`, `hello`, `greet`, `dice`,
`triangle`, both `size-report` programs byte-identical). Above two sites it is pure win.

### Re-evaluation triggers

- **If `%schar-set-runtime` becomes hot.** The mutable-character-vector arm is a call
  where it used to be an inline `%row-major-aset` -- one call per character written, in
  `make-string` fill loops (ironclad's hex conversion is the shape). The answer would be
  a fast path at the SITE (a `ref.test` on the mutable-vector representation before the
  call), NOT a return to inlining the rebuild.
- `subseq` IS one call now (`%subseq-runtime`, `.kb/subseq-runtime.md`), but the rebuild
  runs only where `%arrayp` said no, so `%subseq-core` reaches the string lane DIRECTLY
  while `%subseq-runtime` would re-test `stringp`/`%arrayp`. The spelling stays.

What is left in a site is `%aset` itself, an inline farray / packed-int-vector /
general-array dispatch. Its GENERAL arm is a call too (`_arr_set`,
`.kb/subseq-runtime.md`), taking a site from ~292 to 187 bytes; the packed arms stay
inline on purpose, the integer one being the fused raw-i64 store
(`.kb/packed-integer-vectors.md`). Same lesson as `.kb/wasm-shared-coercion.md` and
`.kb/format.md`'s `%fixed-decimal`: past a few hundred bytes, a per-site expansion
becomes a callee. This one is a spliced Lisp defun, so the JVM and both wasm-GC backends
get it from one definition.

## Pinning tests

- `LispMacroExpanderTest.aStringWriteSiteIsOneCallAndNotAnInlinedSubseqConcatRebuild` --
  the site names `%SCHAR-SET-RUNTIME` and none of `SUBSEQ` / `%STRING-CONCAT` /
  `%ARRAYP`.
- `LispMacroExpanderTest.theStringWriteRuntimeIsInjectedForAnArrayPlaceAndOmittedWithoutOne`
  -- the injection gate, both directions (`row-major-aref` row included).
- Indexed write behavior: the `setf-elt-cross-backend` ci-spec case,
  `LispEvaluatorTest.evalSetfEltDispatchesOverListStringAndVector`,
  `JvmLispCompilerTest.compileSetfEltOnAStringMutatesIt`,
  `WasmLispCompilerIntegrationTest.compileSetfEltDispatchesOverListStringAndVector`.
- The LITERAL rule: the `string-literal-write-cross-backend` ci-spec case (all four
  backends -- `eq`, the three place spellings, the argument case, the nested one), plus
  `LispEvaluatorTest.aStringLiteralIsSharedAcrossEvaluationsOnEveryBackend` /
  `#aWriteThroughAStringLiteralRebindsThePlaceAndLeavesTheConstant` /
  `#aWriteThroughAStringLiteralWithNoVariablePlaceIsAnError` /
  `#aWriteThroughAnAllocatedStringBufferIsStillInPlace` (the guard that the mark stayed
  on literals only and a `make-string` buffer is still written in place, alias included).
- The BULK rule: the `string-literal-bulk-write-cross-backend` ci-spec case (`replace`,
  `fill`, `(setf (subseq ...))`, `nstring-upcase`, and a `make-string` buffer as the
  guard), plus
  `LispEvaluatorTest.aBulkWriteThroughAStringLiteralLandsOnACopyAndLeavesTheConstant` /
  `#aBulkWriteThroughAnAllocatedStringBufferIsStillInPlace` /
  `#rowMajorAsetOnAStringLiteralAsAFirstClassCallIsStillAnError`.
- The `row-major-aref` place: the `row-major-aref-string-cross-backend` ci-spec case (the
  read, a literal write's rebind, a `make-string` buffer's in-place write), plus
  `LispEvaluatorTest.rowMajorArefReadsAStringLikeAref`,
  `JvmLispCompilerTest.compileAndRunRowMajorArefReadsAndWritesAString`,
  `WasmLispCompilerIntegrationTest.compileRowMajorArefReadsAndWritesAString`.
- Mutable identity: the `string-identity-cross-backend` ci-spec case (four blocks, one
  per round, equalp row included),
  `LispEvaluatorTest.aFlippedStringProducerResultHasWritableIdentity`,
  `JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest`
  `.compileAFlippedStringProducerResultHasWritableIdentity` /
  `.compileDisplacedStringViewOverACopySeqResultWritesThrough` /
  `.compileAPrintObjectRoutedPrincToStringResultHasWritableIdentity` /
  `.compileAProducerBuiltStringFoldsAsAnEqualpHashKey`,
  `JvmLispCompilerTest.compileALiteralArgumentProducerCallIsNotFoldedIntoASharedLiteral`,
  `LispMacroExpanderTest.anExpanderBuiltStringPieceIsTheInternalConversionNotThePublicProducer`
  (the spelling guard: a public print name back in an expansion brings the tax back).
- Boundaries: `JvmFfiInteropCompilerTest.aProducerBuiltStringCrossesTheFfiBoundary`,
  `JvmObjcInteropCompilerTest.aProducerBuiltStringCrossesTheObjcBoundary`, the producer
  rows in `JvmJavaInteropCompilerTest.staticCall`.
