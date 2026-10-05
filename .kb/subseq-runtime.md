# `subseq` and general-array element access are shared callees, not per-site code

**Invariant: no `subseq` site emits the array copy loop inline, no `%subseq-core` site emits
the string/list walk inline (wasm `_subseq_str`, JVM `_subseqCore`), and no `aref` / `%aset` /
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

## Bounds check -- every representation, one text, a type-error
**Invariant: `0 <= start <= end <= (length seq)` is checked before anything is copied or
allocated, whatever representation `seq` is in, on every backend, with the interpreter's text
`"SUBSEQ: invalid bounds S, E for KIND of length N"`** (`KIND` = `string` / `list` /
`vector`; `S` and `E` are the bounds as given, printed by `prin1`, an omitted end printed as
`N`; a fill-pointer vector's length is its fill pointer), **as a `type-error`** (CLHS 17.1.1;
SBCL signals one too): datum the first bound outside its range -- `start` outside `[0, N]`,
else `end` outside `[start, N]` (`OperandTypes.subseqStartRefused`) -- expected type that
range, the list `(INTEGER low N)`.

**A bound that is no integer -- a string, a float, a nil start -- or an integer past the int
range is outside its range like any other**, not a separate operand check: every lane compares
the bounds as int INDICES, and a bound's index is its value when it is a fixnum that fits an
int, else -1, which lies outside every range. The report and the slots read the bounds as
given, so `(subseq "abc" "a")` reports `SUBSEQ: invalid bounds "a", 3 for string of length 3`,
datum `"a"`, expected type `(INTEGER 0 3)`, and `(subseq s 0 (expt 2 32))` reports
`4294967296`, not the `0` its low 32 bits read as. SBCL's class and datum for a non-integer
bound match (its expected type is its own `(MOD 4611686018427387901)`, the array index type);
for a bignum bound it reports through `bounding-indices-bad-error` with the cons datum, the
same divergence as an out-of-range fixnum. One deviation from SBCL's order: SBCL checks both
bounds' TYPES before either range, so `(subseq "abc" 5 "a")` is its end's type-error and the
start's range error here. Before, a non-integer bound was the interpreter's
`SUBSEQ expects an integer index` `simple-error`, a slot-less `type-error` on the JVM (a
`ClassCastException` in the unbox) and an uncatchable cast trap on wasm; a bound past the int
range wrapped (`l2i`, `(int)`) on the interpreter and the JVM and trapped on wasm (a bignum
past i31).
- Interpreter: `Environment.subseqBound`. JVM: `_subseqIdx(Object) -> int`, one per class
  (`JvmOperandTypeRuntime.SubseqRuntime`, built on first use; a call per bound rather than the
  test inline at up to six sites). wasm: `WasmEmitHelper.emitBoundIndex` inline (`ref.test
  i31`, else -1: a bignum is past any length a module can hold).
SBCL's own slots differ (`(start . end)` against `(CONS (INTEGER 0 N) (INTEGER start N))`,
its `bounding-indices-bad-error`); the class is what a portable program reads, and it is
pinned against SBCL. Until 2026-10-05 the refusal was condition-less and landed as a
`simple-error`. History: a42 checked the literal-string lane only;
until d13 a list truncated (`(subseq '(1 2 3) 1 5)` -> `(2 3)`), a vector reported through
`AREF` or trapped `allocation size too large`, and a built string threw a JVM
`ClassCastException` / trapped on wasm -- Clojure `.substring` inherited it.

**An omitted `end` is the end argument's nil, read as nil -- never an int sentinel.** The
JVM helpers take `end` as an `Object` (`_subseqCore`, `_subseqCv`, `_subseqEnd`; the
resolve is `JvmSubseqCompiler.emitResolveEnd`), wasm `_subseq` tests its param 2 with
`ref.is_null` (`_subseq_str` and `%subseq-end` always did). Nil used to travel as the int
`-1`, so a GIVEN `-1` read as omitted: `(subseq s 0 -1)` answered the whole sequence on
the JVM for every representation and on wasm for a literal string or a list, and
`write-string :end -1` wrote it all. `Integer.MIN_VALUE` would not have been safe either:
a bound's index can be any int. Cost: wasm modules with a
subseq lane -8 B (the per-cell list test is `ref.is_null` instead of an `i32` compare),
JVM classes -14 B with `_subseqCore`, +5 B with `_subseqCv` alone (`sieve`); programs
without a lane byte-identical (`hello_world`, `pi_approx`, `dom_reactor`, `string`,
`list`, `fib`, ... at the default and `size` levels; `--optimize=off` carries the lane:
-5/-14 B JVM, -8 B wasm).

Where each lane checks:
- **Interpreter**: every representation's arm throws `Environment.subseqBoundsError`, an
  `OperandTypeException.reported` -- the report worded by the built-in, already named so the
  built-in seam keeps it, its datum and type filling the synthesized condition.
- **General / packed / fill-pointer / displaced vector**: the shared Lisp dispatch
  (`LispMacroExpander.subseqDispatch`, both compile paths) resolves its end through
  `(%subseq-end start end (length seq))` (`LispNames.SUBSEQ_END`, compile-path only:
  `JvmSubseqCompiler.compileEnd` / `WasmSubseqCompiler.compileEnd`), before `%array-alike`.
  Not a Lisp `(error "..." ...)`: that lowering (generic `<`/`<=` calls, a concatenation
  per piece) took the JVM `%subseq-runtime` from 659 to 1,234 bytes of bytecode; the
  primitive is int compares and one report block. The vector lane now always reads
  `(length seq)`, which it skipped when `end` was given.
- **JVM string, both representations**: `_subseqCv`'s two arms test through
  `JvmSubseqCompiler.emitResolveEnd` + `emitBoundsTest` and jump to ONE
  `emitBoundsError` block (a plain `RuntimeException` built from chained
  `String.valueOf(int)` + `concat`).
- **JVM, ONE refusal per class**: every lane's `emitBoundsError` is
  `throw _subseqBad(start, end, len, " for KIND of length ")`, the bounds as given
  (`JvmOperandTypeRuntime.SubseqRuntime`, built on first use into the numeric runtime's
  methods). It renders the report through `_lispToString` and, under a landing pad, records
  `{datum, (INTEGER low len)}` in `_teTl`; the pad's type-error arm recognizes a recorded
  exception by identity and reads the slots through `_teSlot`, as for a wrong-type operand.
  Without a pad nothing records. Cost of the non-integer change (bound conversion through
  `_subseqIdx`, the report printing the bounds as given, the per-site report blocks folded
  into `_subseqBad`): bytecode shrinks (a class with the three lanes 774 -> 725 bytes of code),
  the two methods' metadata does not -- +197 B on zlib's class, +221 at `--optimize=size`,
  +264 on `sieve` (`_subseqCv` and `_subseqCore` only); a class with a pad that refuses a
  range +84..108 B (the per-site recording calls and `_subseqRec` are gone). Programs without
  a lane byte-identical (`hello_world`, `pi_approx`, `dom_reactor`, `string`, `fib`, `list`,
  `bignum`, `clos`, `hash`, `mandelbrot`, `matmul`, default and `size` levels). A loop of
  string / literal-string / list / vector `subseq`s ran the same before and after (JDK 25,
  within run-to-run noise).
- **JVM `%subseq-core` lane** (string without the array runtime, and the list): ONE
  per-class helper `_subseqCore(seq, start, end)` built on first use
  (`JvmEmitHelper.emitSharedCall`); a site is a call. It used to inline the whole walk at
  every site -- a program without arrays reaches it from each `subseq` a lowering
  introduced after the array gate was decided (a `format` directive renders through
  several), and with the check inline too `format-directive-exponential`'s class grew
  60,688 -> 65,660 bytes; as a helper it is 32,598. The list arm refuses a negative start
  or start > end before the walk; a list that runs out before `start` or a given `end` is
  found BY the copy walk, and only then is the list counted for the report -- a valid range
  costs no length walk. `%subseq-end` is the same kind of helper, `_subseqEnd`. A program
  that compiles no site carries neither, at every optimize level.
- **wasm string**: `_subseq`'s string branch (immutable) and `_subseq_str`'s
  character-vector arm, through `WasmStringRuntimeBuilder.emitSubseqBoundsCheck` (ONE
  `if`; a42 had three copies of the report). The length is `FUNC_SEQ_LEN`. The report reads
  each lane function's params 1 and 2, the bounds as given.
- **wasm list**: `_subseq`'s list branch, the JVM list lane's shape: the walk `br`s out to
  a block around it (no per-cell flag), and only that block counts the list for the report.
- EH mode: every site pushes the start and end as given (the end nil when omitted), the
  length as an i31 and its `" for KIND of length "` piece and calls ONE landing,
  `_subseq_bad` (`FUNC_SUBSEQ_BAD`, `WasmStringRuntimeBuilder.buildSubseqBadBody`), which
  renders each bound through `FUNC_PRIN1_TO_STR` (the `WasmOperandTypes.pushBound` trick; an
  omitted end as the length), decides the refused bound over the start's index,
  concatenates the pieces and
  throws a `type-error` instance where the module baked the class (a handler landing pad:
  `operandTypeErrorShape`, the `_type_err` gate; `INTEGER` is `Texts.compoundNames`' entry,
  so the expected type's car is `eq` to the program's `'integer`), the instance-less
  `(nil . message)` payload otherwise. Outside EH mode: a bare `unreachable` at the site,
  like every other failure that backend takes when no tag exists to throw on.
- Cost (2026-10-05, wasmtime 49): an EH module with a pad and one subseq lane +76 B (P1),
  the fixture programs +39-47 B; an EH module without a pad SHRINKS (the per-site reports
  became calls): zlib P1 130,289 -> 130,204, component 134,108 -> 134,017, size level
  100,085 -> 100,000; non-EH modules and `hello_world`, `pi_approx`, `dom_reactor`,
  `string`, `list` byte-identical at every shaking level (`--optimize=off` carries the
  unshaken landing: +5 B non-EH, +50 B EH). The non-integer change (the index test at each
  bound, the landing reading the bounds as given): +18 B on a non-EH module with a lane, +41
  on the fixture-shaped EH program with a pad, +72 on zlib (EH, no pad) at both levels, +75 on
  `sort`; P1 and component alike; the same `subseq` loop as the JVM's ran the same (wasmtime,
  within noise).
- **The literal pieces MUST be interned before `stringTable.toByteArray()`**: interned
  lazily inside a runtime body builder, the offsets are recorded but the bytes never reach
  the data segment -- the message comes back with the numbers in place and blanks where the
  text should be. The three pieces (`WasmSubseqCompiler.BOUNDS_PREFIX` and `BOUNDS_COMMA`,
  which `_subseq_bad` cites, and `STRING_LENGTH`, which the sites cite) are interned up
  front in EH mode, droppable, so a module without a report loses their bytes but not their
  ADDRESS SPACE: the shaker cuts a dead range and keeps every address.
- **So the list and vector reports add NO data entry.** Any new entry -- eager, or lazy at
  the first site -- moves every later address of nearly every EH-mode module: even
  `(print (handler-case (car 1) (error () 2)))` compiles subseq sites (in bodies the shake
  then removes) with the charvec gate open, so a site-gated piece still shifted it by the
  pieces' 46 bytes. Instead `WasmStringRuntimeBuilder.emitKindOfLength` CUTS
  `" for "` and `" of length "` out of `" for string of length "` (`_str_build` over bytes
  `[0, 7)` and `[11, 24)`: a string's first and last bytes are its frame, and
  `_string_concat` copies only what lies between) and builds the kind word from constants
  (`array.new_fixed`, id 0 -- a transient, concatenated at once). The cost is code, which
  dies with its body.
- The `--no-gc` scalar wasm backend (`NoGcWasmCompiler`, outside `CiSpecE2eTest`'s four)
  is untouched: `.kb/no-gc-scalar-wasm.md` names its `subseq` as unchecked.
- Pins: `SubseqBoundsFixture` (every representation, the full text, the type-error's
  datum and expected type, a non-integer bound's row, a past-the-int-range row: 2^30,
  2^32, 2^32 + 3, 2^62, 2^64;
  `LispEvaluatorTest#subseqSignalsInvalidBoundsOnEveryBackend`,
  `JvmLispCompilerTest#compileAndRunSubseqSignalsInvalidBounds`,
  `WasmLispCompilerIntegrationTest#subseqSignalsInvalidBounds` -- P1 and component),
  `JvmLispCompilerTest#compileAndRunSubseqWithoutTheArrayRuntimeChecksItsBounds` (the
  `_subseqCore` lane's slots),
  `ClojureInteropTest#aSubstringOfABuiltStringRefusesARangeOutsideIt`, ci-spec
  `subseq-refuses-a-bad-range-in-every-representation` (SBCL's answers, the class by a
  `type-error` clause; a bound past the int range is a column of it, except a list's end of
  2^30 / 2^32, which SBCL conses the list up to before refusing),
  `subseq-refuses-a-non-integer-bound` (SBCL's class and datum, the
  bounded string operators included).

## Bounded string operators -- the SAME refusal, named `SUBSEQ`
`write-string` / `write-line` / `string-upcase` / `-downcase` / `-capitalize` with a
`:start` / `:end` outside the string refuse as `subseq` refuses the range as written: the
same `type-error`, text and slots, on every backend, through a direct call and through
`funcall`. The compile paths lower them onto `subseq`
(`LispMacroExpander.lowerWriteStringBounds`, `.expandBoundedCaseConversion`), so the text
names `SUBSEQ`; the interpreter's builtins (`Environment.boundedCaseConversion` and the
`write-string` / `write-line` arms) call `subseqBoundsError`, with `kind` `string`. SBCL
signals a `type-error` for all five and its text names no operator either.
- **Naming the operator was measured and not done.** The refusal is a shared landing --
  wasm `_subseq_bad` bakes its `"SUBSEQ: invalid bounds "` prefix and sits at a fixed
  function index (a parameter or a second landing moves every EH module's bytes), the JVM
  lanes share one `emitBoundsError` -- and the one thing a portable program reads is the
  class and the slots, which an operator word does not carry. The interpreter used to name
  the operator (`STRING-UPCASE: bad bounding indices 3..1`, a `simple-error`; and
  `WRITE-STRING` for `write-line`); that text is gone, not kept apart from the compiled one.
- **The case conversions cut the window FIRST** (`__bcc_mid`): the lowering used to take
  `(subseq s 0 start)` before the window, so `:start 9` on a length-5 string reported
  `invalid bounds 0, 9` on the compiled paths and `9..5` in the interpreter. The window's
  own `(subseq s start end)` is the range as written; the two outer cuts are then in range.
  Cost: one more local per site -- wasm +4 B (a two-site program: P1 26,721 -> 26,725,
  component 27,887 -> 27,891), the JVM class a few bytes (25,494 -> 25,508); programs with no
  bounded conversion are byte-identical (`hello_world`, `pi_approx`, `zlib`, a
  `write-string :start` program, on JVM / P1 / component).
- Pins: `BoundedStringBoundsFixture` (`LispEvaluatorTest`, `JvmLispCompilerTest`,
  `WasmLispCompilerIntegrationTest` -- P1 and component), ci-spec
  `bounded-string-operators-refuse-a-bad-range` (SBCL's class; a 2^32 end and a 2^62 start
  included), `subseq-refuses-a-non-integer-bound` (a nil `:start`, direct and through `funcall`).
- A non-integer bound is refused like `subseq`'s ("Bounds check" above), a nil `:start`
  included: only a nil `:end` means the string's length, a nil `:start` is no bound and is the
  same `type-error` (datum `NIL`) on every backend, SBCL's too. `lowerWriteStringBounds` hands a
  given `:start` to `subseq` as written, and the first-class wrappers read it with
  `getfKwDefault` (`(getf kw :start 0)`: the default stands for an ABSENT indicator only; the
  older `getfKwOr` reads a present nil as the default).
- A program that carries the Gray streams library routes a `write-line` with bounds through
  `%gray-write-line-dispatch` (`GrayStreamsLibrary`), so the lowering above is not the only
  reader of `:start`: the helper's non-instance arm passes the bounds on as written
  (`start-p` tells a given nil from an omitted one). A Gray INSTANCE still reads a nil bound
  as absent (user `stream-write-string` methods default `start`), in the interpreter too.
  `BoundedStringBoundsFixture` defines a Gray class so its rows exercise this arm; the
  ci-spec case does only because the corpus program does (a case run alone does not).
- Programs with no `:start` stay byte-identical (`write-string` / `write-line` plain, `hello`);
  a `:start` site gets smaller (no `or` around the bound): a literal-bound program JVM 9,889 ->
  9,799, P1 5,482 -> 5,455, component 6,619 -> 6,592; a `funcall` program JVM 33,266 -> 32,584,
  P1 40,879 -> 40,129, component 42,117 -> 41,363.
- The sequence operators refuse every bad bound with this same `type-error` and text, call
  position and first class, through one check of their own before the walk (`%check-bounds`,
  `.kb/sequence-bounding-keywords.md`, "Every bound is checked once"); the string comparisons and
  the `nstring-*` conversions refuse a nil `:start` the same way (`.kb/characters-code-points.md`,
  "String comparison family").

## Tests
- `LispMacroExpanderTest.aSubseqSiteIsOneCallWhenTheProgramCarriesTheSharedDispatch`,
  `.theSharedSubseqDispatchAnswersTheSameThingAsTheInlinedOne` (the helper must not call `subseq`
  itself), `.theGeneralArrayGateNamesTheOperatorsThatCanProduceOne`.
- `WasmLispCompilerTest.anElementAccessSiteDoesNotCarryItsOwnCopyOfTheSharedRuntime` -- the byte
  budget; nothing else notices, every arrangement compiles and runs correctly.
- Displaced-array and fill-pointer cases in `WasmLispCompilerIntegrationTest`,
  `JvmLispCompilerTest`, `ci-spec.yaml`.
