# Fill-pointer / adjustable / displaced arrays (`copy-array` surface)

Split out of todo-071 (itself split from the
cl-utilities residue todo-065). This is the extended-array surface that
cl-utilities `copy-array` touches:

- `make-array` options `:fill-pointer`, `:adjustable`, `:displaced-to` /
  `:displaced-index-offset`.
- `fill-pointer` (+ `setf`), `array-has-fill-pointer-p`, `adjustable-array-p`,
  `array-element-type`.
- `vector-push` / `vector-pop` / `vector-push-extend`.
- `adjust-array`, `array-displacement` (two values), `copy-array` itself.

## Status (2026-07-06)

**ALL sub-steps DONE on all four backends** (interpreter, JVM, WASM Preview 1,
WASM component): the fill-pointer surface, `adjust-array`, and `:displaced-to`
displacement + `array-displacement`. The verbatim cl-utilities `copy-array`
definition runs everywhere (pinned by
`LispEvaluatorTest.clUtilitiesCopyArrayRunsOnInterpreter`,
`JvmLispCompilerTest.compileClUtilitiesCopyArray`,
`WasmLispCompilerIntegrationTest.compileClUtilitiesCopyArray`, and the
`fill-pointer-arrays-cross-backend` ci-spec case). `--no-gc` rejects the whole
surface with its usual clear compile error (arrays are ineligible on the scalar
backend: `--no-gc: unsupported operation 'vector-push' ...`; the new names go
through the same default path), satisfying the todo-71 "gate explicitly"
acceptance without a dedicated gate.

**Fill-pointered STRINGS (mutable character vectors, ALL backends
2026-07-18)**: `make-array :element-type 'character` — with or without
`:fill-pointer`/`:adjustable` — builds a mutable string everywhere. On the
interpreter it is a mutable `LispString` (char[] buffer + fill pointer +
adjustable flag). On the COMPILED backends it is the GENERAL array
representation holding character elements, marked "character vector" —
JVM: a **length-4** slot-0 header `Object[]{dims, fp, adj, null}` built by
`_charVecMake` (displacement detection tightened to `header.length > 4`,
i.e. displaced ⇔ length 5, or 7 for the string VIEW added later, at
`emitResolveDisplacement` / `_arrayDispTarget` / `_arrayDispOffset`); WASM: the **meta offset i31 == 1**
(an ordinary array's is 0; a displaced array's data slot is a cell, so the
marker is unambiguous, and `%array-disp-offset` now reports the offset only
when the data slot IS a cell). The whole fill-pointer surface
(push/pop/push-extend, setf fill-pointer, `%array-become`, `_rmGet/_rmSet`)
runs on it unchanged, and the marker survives `adjust-array` because become
mutates the existing header in place.

**Mutability is the MARKER's, not the fill pointer's (todo-610, 2026-08-31)**:
a `make-array :element-type 'character` (and therefore a `make-string`) with no
`:fill-pointer` leaves the fill-pointer slot NIL on every backend, and
`setf char`/`setf aref` still write every slot. The JVM used to default that
slot to the capacity, so `(array-has-fill-pointer-p (make-string 3))` answered
`T` there and `NIL` on the other three backends and in SBCL 2.2.9 -- and the
value could not be told apart from a `:fill-pointer`-built one, which is what
`(typep s 'simple-string)` and `simple-string-p` now ask
(`.kb/declarations-type-checks.md`, "`typep` checks SIMPLICITY"). `_strv`
already read `dims[0]` for a nil slot and `_subseqCv` already CLEARED it so a
`copy-seq` result is simple, so the invariant was half in place already.

**Every array-info reader answers for a string, in all three of its
representations (todo-464, measured 2026-09-02)**: `array-dimensions`,
`array-rank`, `array-total-size`, `array-dimension`, `array-row-major-index`,
`array-has-fill-pointer-p` and `adjustable-array-p` accept the immutable string
that carries no header, the mutable character vector and the displaced string
view, on all four backends. The three shape readers and the row-major index all
lower through `array-dimensions` (`LispMacroExpander.expandArrayRank` and
friends), so ONE string arm per backend carries them --
`JvmArrayRuntimeBuilder`'s `_arrayDims` (an `instanceof String` arm over
`_strCharCount`, ahead of the `ArrayList` header cast) and
`WasmArrayCompiler.compileDims` (a `ref.test TYPE_STRING` arm over
`_str_char_count`), the character vector and the view falling through to the
general header path that already held their dims. The two predicates instead
guard the header read with a shape test and answer NIL for anything else
(`emitHeaderSlotToBool`; `compileHasFillPointer` / `compileAdjustableArrayP`),
so a headerless string is nil rather than a cast trap.

The dimension is the **CAPACITY**, never `length`: a capacity-5 character
vector with fill pointer 2 answers `(5)` / rank 1 / total size 5 while `length`
answers 2. SBCL 2.2.9 answers the pinned program identically except
`adjustable-array-p` over a DISPLACED view -- SBCL answers `T` for any
non-simple array (a fill-pointered general vector included), while rontolisp
reports the `:adjustable` argument verbatim, so it answers `NIL`. Both are
conforming; CLHS leaves "actually adjustable" implementation-defined. Pinned by
`LispEvaluatorTest#theArrayShapeReadersAcceptEveryStringRepresentation`,
`JvmLispCompilerTest#compileTheArrayShapeReadersAcceptEveryStringRepresentation`,
`WasmLispCompilerIntegrationTest#arrayShapeReadersAcceptEveryStringRepresentation`
and the `string-array-shape-readers-cross-backend` ci-spec case.

**A DISPLACED view carries its own fill pointer and `:adjustable` flag
(`.todo/647`, 2026-09-02)**: see "A displaced view is not a BARE view" below.
Only `:initial-element` / `:initial-contents` are still refused alongside
`:displaced-to`, which is the one combination CLHS forbids.

**A COMPUTED `:element-type` (`.todo/219`, 2026-07-30)**: the recognizers
above read the designator at EXPANSION time, so a `:element-type` held in a
variable or produced by a call (`(stream-element-type s)`) used to fall
straight through to the general array — and with `stringp` false, a
`read-sequence` into it read BYTES from a character stream.
`LispMacroExpander.lowerRuntimeElementTypeMakeArray` (tried first by
`Jvm/WasmArrayCompiler`, before the `:initial-contents` lowerings) turns such
a call into a runtime `member` dispatch: the character designators pick the
`:element-type 'character` allocation above, anything else drops the keyword
and takes the general one — the packed float / `(unsigned-byte N)` arrays are
OPTIMIZATIONS of the general array, so that is always a correct answer, just
not always the packed one. "Literal" here means a `(quote ...)` form, the
unquoted `(unsigned-byte 8)` compound shape, or a bare symbol in
`LITERAL_ELEMENT_TYPE_NAMES`; the interpreter needs no lowering because its
`make-array` reads the designator at run time already.

String behavior comes from ON-DEMAND NORMALIZATION into the immutable
runtime string: JVM `_strv(Object)` (JvmArrayRuntimeBuilder, emitted under
the same array gate) renders the active prefix quote-framed; WASM
`_charvec_to_str` (a fixed always-emitted function right after
`FUNC_WRITE_STR_GC` — `FUNC_VEC_BASE`/`FUNC_USER_BASE` shifted by one,
reusing the unary `TYPE_CALLABLE_BASE + 0` signature, capture-aware scratch
so mid-capture normalization cannot clobber `*-to-string` output). Insert
points: the string-op compile sites (subseq, string=/-equal -- NOT char/schar/elt,
which read the ELEMENT through `_charRef` / `_str_char_ref` since 2026-08-31
rather than rendering the vector per index, `.kb/string-index-cost.md` --
case/trim/concat, write-string, string designator, read-from-string,
make-string-input-stream, intern, make-symbol — the last four were WASM-only
until todo-208 made a plain `make-string` result reach them on the JVM too,
where `_readFromString` and `intern`/`make-symbol`'s quote strip both
`checkcast String` and so threw `ClassCastException` on a char vector;
`%make-string-input-stream` was the one todo-208 named but did NOT wire on the
JVM, found in 2026-08 by jose, whose every string comes out of trivial-utf-8's
`make-string` + `(setf char)` loop and went straight into cl-json's
`decode-json-from-string`), plus
the shared runtime bodies — JVM `emitArrayBranch` of
`_lispToString`/`_lispToDisplayString` (which also covers equal-hash-table
keys, keyed by rendered string) and `_eqv`'s equals fallback; WASM the
entries of `_equal`, `_hash`, `_print_val`, `_princ_val`.
On the JVM everything is gated on `programUsesAnyArrayOp || usesFloatArray`
(`Ctx.usesArrays`), so array-free programs stay byte-identical; on WASM the
helper is always emitted (all module bytes shifted once, flag-dimension
byte-identity contracts unaffected).

**A PREDICATE does not normalize (todo-342, 2026-08-12)**: `stringp` was in
that WASM list, and it is the one caller that never wanted the string. Its
`TYPE_CELL` arm called `_charvec_to_str` and `ref.test`ed the result for
`TYPE_STRING` — an O(1) question answered by rendering every element into a
fresh string and keeping one bit, re-paid on every call, for the whole life of
a value that never leaves the mutable representation. Measured (wasmtime 47,
200,000 calls): 10.2 s for `(make-string 8192)`, 1.26 s for 1024, 0.11 s for
64, against 4 ms for the `copy-seq` of the same 8192 vector (an ordinary
`TYPE_STRING`) — exactly linear, while the interpreter and the JVM were flat.
`(length s)` on the same value was already O(1); only the type predicate
walked.

The shape decision is therefore its own runtime function, **`_charvec_p`**
(`FUNC_CHARVEC_P`, `WasmStringRuntimeBuilder.buildCharvecPBody`, appended
right after `FUNC_CHARVEC_TO_STR`, `((ref null eq)) -> i32` reusing
`TYPE_RAT_GET`): the eight `ref.test`s down to the marker compare and nothing
else, 213 bytes of WASM (`_charvec_to_str` lost 158 of its 653 to the split).
`stringp` calls only that; `_charvec_to_str` opens with it and then renders,
so the marker invariant has ONE owner rather than one copy per caller.

The alternative — inlining the tests at each `stringp` site, no new function
index — lost on a MEASUREMENT, not a preference: in the same 200,000-iteration
harness a bare truth test is 3-4 ms and the same loop with one call to an
eqref-returning defun is also 3-4 ms, so the call frame is under 5 ns; of the
9 ms the character-vector predicate now costs, essentially all of it is the
eight tests, which inlining would still pay. It would buy ~1 ms per 200,000
calls in exchange for one copy of the invariant per site plus ~200 bytes each.

Same answers for every shape (the general/adjustable/multi-dimensional array,
the packed vectors, the displaced array, hash table, struct, instance, closure,
stream that share the `TYPE_CELL` box), now flat in the length: 9 ms for all of
1, 1024 and 8192 (`--component` and `--optimize` alike). It costs no bytes
either: the full runtime module is 58 SMALLER (`size-report` hello_world
121,463 → 121,405, pi_approx 121,663 → 121,605 — the `stringp` sites each
dropped a `ref.test`), and a program that never reaches the arm is
BYTE-IDENTICAL at `--optimize` (both `size-report` rows and all eight
`.kb/optimize-dead-code-elimination.md` print-fold rows unmoved). Pinned by
`WasmLispCompilerIntegrationTest`'s
`compileStringpClassifiesEveryCellShape` (the shapes) and
`compileStringpOverACharVectorIsConstantTime` (the complexity class, as a
ratio between two lengths so the pin does not depend on the machine).

Not done, and deliberately: the OTHER callers (and the JVM's `_strv` at the
same sites) still re-render their argument on every call, because the rendered
string is never written back into the cell. Caching it there would fix that
class outright, but a character vector is MUTABLE, so every write that can
reach it would have to invalidate the cache and missing one is silent wrong
output rather than slow output — a trade that wants its own measurement first.
`.todo/343`.

Mutation flows through SHARED expansions (`LispMacroExpander`):
`expandReplace`, `expandFill` and `expandScharSetFunctional` branch at runtime on
`(%arrayp seq)` — a vector (char vector included) is written in place via
`%row-major-aset` + `elt` and returned; an immutable string keeps the
functional rebuild (fresh string; the setf form still requires a variable
place). `fill` is `replace` with a constant source and one extra arm: a LIST
target has its `car`s rewritten with `rplaca` (chipz and salza2 only ever fill
vectors, but a half-contract builtin is worse than none), and the string rebuild
splices a `(make-array n :element-type 'character :initial-element item)` filler
between the untouched head and tail. The interpreter writes a string in place
through `LispString.setCharAt`, so that one deviation is compile-path-only,
exactly as it is for `replace` — with ONE exception since todo-580: a string
LITERAL is never written in place on any backend, `%schar-set` included, because
the literal is the source constant (`.kb/string-write-runtime.md`, "A string
LITERAL is never written"). `replace`/`fill`/`nstring-*` do not yet honour that
and still corrupt a literal on the interpreter — `.todo/581`. `nstring-upcase` / `nstring-downcase` /
`nstring-capitalize` (todo-402) join that family rather than forming a second
one: they are prelude Lisp that folds with the NON-destructive sibling and then
walks `%nstring-replace`'s `(setf (aref s i) c)` over the result, so they inherit
the same split — a mutable character vector is written in place and comes back
`eq` on every backend, an immutable string is rebuilt on the compile paths while
the interpreter folds the caller's own object. The RETURN value is correct on all
four either way, which is what portable code (chunga's
`(intern (nstring-upcase s) :keyword)`) consumes; `.kb/string-write-runtime.md`.

`lowerCharacterInitialContentsMakeArray` lowers rank-1 character
`:initial-contents` to a fresh string copy (`subseq` of a stringp contents,
else `coerce 'string`) — both compilers try it BEFORE the general
`:initial-contents` lowering. Default `:initial-element` for a char vector
is `#\Space`. Lite residue: `adjust-array` on a NON-adjustable char vector
returns a general (unmarked) vector; `#'make-array`'s variadic wrapper
(`apply` path) has no `:element-type` cue, so it builds an unmarked vector;
char reads past the fill pointer see the rendered (fp-bounded) content.
E2E: ci-spec `mutable-strings-cross-backend`; unit: the
`compileCharVector*` / `compileScharSetfMutatesCharVectorInPlace` /
`compileJzonAccumulatorPattern` sets in both compiler tests.

**A PLAIN `(make-string n)` is a character vector too (all backends,
todo-208)**. `LispMacroExpander.expandMakeString` — shared by the
interpreter and both compilers — lowers to
`(make-array n :element-type 'character :initial-element c)`, so every
make-string result IS the mutable representation above. `make-sequence`
reaches it through the same door (`expandMakeSequence` maps the string
family onto `make-string`).

Why, and the trigger to revisit: the old lowering was a `dotimes` that
`concatenate`d ONE character per iteration into a fresh IMMUTABLE string.
That was O(n^2) to allocate, and — because the result was immutable — the
`(%arrayp seq)` test in `expandReplace` / `expandScharSetFunctional` was
false, so `(replace buf x)` and `(setf (subseq buf ...) x)` took their
FUNCTIONAL branch, built the right string, and had it discarded in statement
position. `(setf (char buf i) c)` only appeared to work because the
functional branch `setq`s the rebuild back into the variable, which an alias
never sees. The visible casualty was s-sql's verbatim `strcat` ("allocate a
`make-string` buffer, `replace` into it, return it"): every S-SQL form
carrying a non-literal value — `:insert-rows-into`, a variable, `(* 3 100)`
— reached PostgreSQL as a BLANK string of exactly the right length. Nothing
signalled; the server answered `WARNING: Empty query sent.` and the row was
not inserted. If a future change makes make-string build anything other than
the character vector, that whole class of "allocate a buffer, write into it"
CL code silently regresses again. Pinned by the ci-spec case
`make-string-mutability-cross-backend` (the four write shapes plus what a
buffer is read through) and by `PostmodernE2eTest`'s `runtimeSql*` leg on
the interpreter, the JVM and the component.

Consequences of the lowering, all decided rather than inherited:

- **The JVM array gate now includes `make-string`.** `Ctx.usesArrays` is
  `programUsesAnyArrayOp(program) || usesFloatArray || usesIntArray`
  (plus the forced-group term below), and both backends'
  `programUsesAnyArrayOp` list `MAKE-STRING` and
  `MAKE-SEQUENCE` alongside `MAKE-ARRAY` (they lower to it during
  `compileExpr`, after the scan runs). So a string-only program that
  allocates a buffer does pull in the array runtime and grows. That is
  deliberate: the alternative — lowering conditionally on a scan for "does
  this program mutate the string" — cannot see mutation through a call
  (`strcat` mutates its own local), which is precisely the bug. Array-free
  programs that never mention make-string are still byte-identical
  (`.kb/emitted-output-determinism.md`). `make-string` also joined
  `BuiltinFunctionWrappers.ARRAY_FILL_POINTER_FUNCTIONS` so its wrapper is
  injected exactly when the runtime it calls is emitted.
- **`eq`/`eql` on a make-string result answers like any other computed
  string** — content on the interpreter and the JVM, identity on WASM — and
  that is the pre-existing general string divergence, not a character-vector
  one: `(eq "abc" (concatenate 'string "ab" "c"))` is already `T` on the
  interpreter/JVM and `NIL` on both WASM backends (WASM strings carry an id
  field; `_eq` compares it). `(eq s s)` is `T` everywhere. CL leaves `eq` on
  strings unspecified, so no backend is wrong and the ci-spec case
  deliberately does not cover it; the trigger to revisit is WASM gaining
  content-`eq` for strings generally, which would close this row with it.
- **`--no-gc` is unaffected**: `make-string` was already an unsupported
  operation on the scalar backend and still is (its `make-array` takes float
  element types only).
- **The functional branches stay.** A LITERAL string is still immutable, so
  `expandReplace` / `expandScharSetFunctional` keep their `%arrayp`-false
  paths for `(replace "abc" ...)`; what changed is which values reach them.

## The JVM array gate is a CONSEQUENCE, not a prediction (todo-209)

**The invariant: on the JVM the array runtime is emitted whenever the emitted
bytecode calls it, no matter which lowering introduced the call.** Before
2026-07-29 it was emitted when `programUsesAnyArrayOp` -- a scan of the SOURCE
program for a hand-maintained list of operator names -- said so. Several
lowerings introduce an array primitive only during `compileExpr`, i.e. AFTER
that scan, and when one of those was a program's only array use the class
carried an `invokestatic` to a method that was never generated. JVM method
resolution is lazy, so it survived verification and threw `NoSuchMethodError`
the first time the branch ran. The list was right up to whichever lowering was
added last without a matching entry, and it could not be right by construction.

How it works now, in `JvmLispCompiler`:

1. The scan stays -- it is still what decides `Ctx.usesArrays`, and that flag
   changes CODE, not just emission (the `_strv` normalization at the string-op
   sites, the character-vector arm of `stringp`, the array print branch), so it
   has to be known before any body is compiled.
2. After the class bytes are assembled, `JvmClassShaker.unresolvedSelfMethods`
   (in `am.ik.jvm`, language-independent) walks every emitted body and reports
   each own-class method that some `invoke*` names but the class does not
   declare.
3. A reported name is matched against the per-group rosters
   (`JvmArrayRuntimeBuilder.METHOD_NAMES`, `JvmHashRuntimeBuilder.METHOD_NAMES`,
   the eval runtime's four methods + `_lookup`). A match means that gate
   under-predicted, so `compile` RE-RUNS itself with the group forced on: the
   result is the build a source that DID mention an array operator would have
   produced, not merely one that no longer crashes. Each retry strictly grows
   the forced set, so the loop terminates.
4. Anything left unmatched is an internal inconsistency no re-run can fix, and
   the compile fails loudly naming the call and its callers instead of handing
   the user a class that dies later.

**The check covers EVERY caller, wrappers included (todo-210).** It briefly did
not: a call referenced only by injected built-in wrappers was skipped, because a
wrapper is injected in every class whether or not the program mentions the
operator and most of them dragged in the array runtime (`#'+`, `#'reverse`,
`#'sort`, `#'subseq`, `#'string=`, ... ~33 of them called `_aref1`) -- without
the skip every class carried the whole array runtime, ~35 KB. The skip was sound
(each reference sat behind a runtime type test for a value the absent runtime
cannot construct) but it cost the check its strength: a genuinely reachable
dangling call inside a wrapper was invisible, and `#'funcall`'s `(apply f r)`
was exactly that shape -- caught only because it needed the eval runtime
UNCONDITIONALLY, which is reference-gated so wrapper and runtime are decided by
the same fact.

The skip is gone because its cause is: **each lowering that dispatches over the
sequence representations now takes an `arraysExist` flag and drops the array arm
when it is false** -- the same move as `expandClassOf`'s `hash-table-p` clause
below and `JvmStringpCompiler`'s character-vector arm. The interpreter and both
WASM backends pass `true` (their array primitives are unconditional); the JVM
passes `Ctx.usesArrays`. The gated lowerings, all in `LispMacroExpander` unless
noted:

| lowering | what the flag drops |
| --- | --- |
| `expandCoerce` | the vector scan behind `'list` / `'string` (`coerceVectorToList`) |
| `expandElt` | the `aref` arm of the string/list/array dispatch |
| `expandMap` | the same `aref` arm in the per-element read |
| `seqResultDispatchForm` (private; `wrapSortForStringSeq`, `expandReverse`, `expandRemoveDuplicates`, `expandRemove`, `expandRemoveIf`, `expandRemoveIfNot`, `expandSubstitute` forward the flag) | the `__seq_vec` binding and the `(coerce __seq_res 'vector)` rebuild |
| `expandSubseqCompat` | the whole rewrite -- it exists only to reach the array arm, so it returns `null` and the compiler emits its plain `%subseq-core` |
| `expandReplace` | the destructive `%row-major-aset` loop, leaving the functional string rebuild |
| `expandVectorp` (todo-605) | the whole ARRAY arm, leaving `stringp` alone -- once `vectorp` checks the RANK its array arm reads `array-dimensions`, and `vectorp` has an injected first-class wrapper EVERY program carries until the shaker runs, so an ungated read there put `_arrayDims` in `(print 1)` |

Reading and BUILDING are gated differently, and the distinction is the reason
this is sound. Dropping a READ of an array element is a local fact: no array
exists, so no read of one can run. Dropping a form that CONSTRUCTS an array is
not -- only the caller's guard can say it is dead. So `expandCoerce` gates the
reading direction only, and the one place that drops a builder is
`seqResultDispatchForm`, which rebuilds a vector result exactly when the INPUT
was a vector. Every other coerce-to-vector keeps its builder and is therefore
still caught by step 3: a source-level `coerce` raises the gate by name, and
`(map 'vector ...)` expands to an ungated `expandCoerce` whose `_arrayMake` call
comes from a real method and forces the re-run.

`(print (+ 1 2))` went from 186,066 to 127,630 bytes (-31%) on this, and a
reference from anywhere -- user code, a defun, a lowering, a wrapper -- is now
program-dependent evidence that does force the gate, even where the branch would
have been dead; conservative in the safe direction. Pinned by
`JvmLispCompilerTest.anArrayFreeProgramReferencesNoArrayRuntimeHelper` (nothing
dangles in an array-free class) plus
`compileSequenceOperatorsWithoutTheArrayRuntime` (the survivors still answer for
lists and strings).

### The array-gated wrapper set is complete (todo-523)

Ten wrapper bodies were never behind the `arraysExist`-flag dispatch the table above
describes, so they called `_aset1`/`_charVecMake`/`_arrayMake`/`_aref1`/`_arrayDims`
UNCONDITIONALLY: `fill`, `coerce`, `vector`, `read-sequence`, `write-sequence`, `svref`,
`array-rank`, `array-dimension`, `array-total-size`, `array-row-major-index`. Every one
of them is injected in every class whether or not the program mentions it (same as the
~33 flag-gated wrappers were before todo-210), so an array-free program's finished class
called methods it never declared and step 3 forced `GROUP_ARRAYS` on and re-ran the whole
compile -- for a program with no array in it. `(print (mapcar (lambda (x) (* x x)) '(1 2
3)))` was 13,654 bytes for exactly this reason.

Fixed the `#'funcall`/`APPLY_USING_FUNCTIONS` way (`.kb/eval-runtime.md`): reference-gate
wrapper and runtime on the same fact instead of flag-gating the body. All ten joined
`BuiltinFunctionWrappers.ARRAY_FILL_POINTER_FUNCTIONS`, so each wrapper is excluded
exactly when `programUsesAnyArrayOp` is false; `LispMacroExpander.usesGeneralArrayOp`
(the JVM scan `programUsesAnyArrayOp` calls) already named `coerce`/`vector`/`svref`/
`array-rank`/`array-dimension`/`array-total-size`/`array-row-major-index`, so only
`fill`/`read-sequence`/`write-sequence` were new there -- and `WasmLispCompiler`'s own
`programUsesAnyArrayOp` (which the same `ARRAY_FILL_POINTER_FUNCTIONS` set gates on
WASM) got the same three added, so the two scans cannot drift and a program that takes
`#'fill` as a value with no other array op keeps its wrapper on both backends. Pinned by
`JvmLispCompilerTest`'s `aProgramThatNeverNamesAnArrayOperatorCarriesNoArrayRuntime` /
`namingOneOfTheArrayWrappersBringsTheArrayRuntimeBack`.

The packed `_fv*` / `_iv*` tiers need no equivalent: `Ctx.usesArrays` is true
whenever `usesFloatArray` / `usesIntArray` is, and an `aref` only compiles to
`_ivAref1` / `_fvAref1` when that tier is emitted, so a wrapper body can never
name one the class lacks.

The `%class-designator` dispatch (what `class-of` lowered to directly before
the metaobject migration) was the one lowering whose gated call was NOT behind
such a test from the gate's point of view: its `cond` chain included a
`hash-table-p` clause unconditionally, so every class that compiled one
referenced `_hashP`. `expandClassDesignator` now takes a `hashTablesExist` flag
(the interpreter and both WASM backends pass `true`; the JVM passes
`Ctx.usesHashTables`) and drops the clause when no hash table can exist -- the
same reasoning that keeps the character-vector arm out of a compiled
`stringp`.

Why this shape and not the alternatives: scanning the expanded program is not
available (the expansions are lazy, per-site, inside `compileExpr`), and
emitting the group unconditionally would end the byte-identity of array-free
programs (`.kb/emitted-output-determinism.md`). Step 2 costs one linear pass
over the class on every build; step 3 costs a second compile only for a program
that actually trips a gate.

`(setf (elt s i) v)` is the worked example, and it needed a semantic fix too:
`expandSetf`'s `ELT` branch yielded `(%aset seq i v)` for everything non-`consp`,
so a STRING target reached `%aset` -- `NoSuchMethodError` on the JVM (no gate
name in the source at all), `cast failure` trap on both WASM backends, and only
the interpreter got it right. The branch now carries the same three-way dispatch
`(setf (aref s i) v)` has -- `consp` -> `rplaca`, `stringp` -> the `schar-set`
rebuild, else `%aset` -- with the same lite restriction: only a VARIABLE place
takes the string arm (the rebuild of an immutable string `setq`s the result
back), so a variable place dispatches on the variable itself and any other place
expression keeps the two-way list/array dispatch over a temp. Pinned by the
`setf-elt-cross-backend` ci-spec case, `LispEvaluatorTest`'s
`evalSetfEltDispatchesOverListStringAndVector`, `JvmLispCompilerTest`'s
`compileSetfEltOnAStringMutatesIt` (the gate) plus
`compileSetfEltOnAVectorAndAList`, and
`WasmLispCompilerIntegrationTest.compileSetfEltDispatchesOverListStringAndVector`.

**The string arm is a CALL now, not an inlined rebuild** -- `%schar-set-runtime`,
one spliced defun per program, `.kb/string-write-runtime.md`. That is what the
"costs that came with it" below are measured against; both of them shrank by more
than an order of magnitude when it landed.

Two long-latent bugs of the same family surfaced the moment step 2 ran over the
corpus, both now fixed at the source rather than by a retry:

- **`#'funcall` died with `NoSuchMethodError` on the JVM.** Its injected wrapper
  body is `(apply f r)`, which compiles to the eval runtime's `_apply`, but the
  wrapper was emitted in EVERY class while `_apply` stayed gated on the SOURCE
  mentioning `eval`/`apply`. `(reduce #'funcall fns)` -- cl-utilities' `compose`
  -- was the shape that hit it. The rule the parse-integer / read-from-string
  wrappers already followed now covers it: a wrapper whose body needs a gated
  runtime is injected exactly when the program references the operator as a
  value, and that same reference forces the runtime on. Pinned by
  `JvmLispCompilerTest.compileFuncallAsAFirstClassValue`.
- **`_tlsConnect` instantiated a constructor that was not always emitted.** The
  whole socket group is emitted for any TCP/TLS operator, but `<init>` was gated
  on `tls-connect` alone; a `tcp-connect`- or `tls-listen`-only program declared
  no constructor. `needsInstanceCtor` follows the socket gate now. (The
  X509TrustManager interface and its three methods stay on `usesTlsConnect`:
  nothing calls them from bytecode -- JSSE does -- and only a `tls-connect` call
  site can reach `_tlsConnect`.)

Costs that came with it, both deliberate:

- Any program using `(setf (elt ...))` now pulls in the array runtime, because
  the string arm's `schar-set` rebuild reaches `%arrayp`/`%row-major-aset`. Same
  trade as `make-string` above.
- The `_top$N` chunk budget dropped from 40000 to 24000. The string arm cost
  ~6 KB per `(setf (elt ...))` site, which took the ci-spec corpus's largest
  single top-level form to ~39 KB -- and a single form cannot be split, so the
  budget has to leave room for it under the JVM's 65535-byte method cap. To
  re-measure, compile with the budget set to 1 (every top-level form gets its own
  chunk) and read `-Drontolisp.jvm.debug-method-sizes=true`, which now ranks the
  top-level chunks alongside the defuns and lambdas.

  **Re-evaluation trigger, and the reason no longer holds as stated**: with the
  arm out of line the marginal JVM cost of one more `(setf (elt ...))` site is
  **293 bytes, not 5,042** (measured 2026-08-08, `.kb/string-write-runtime.md`),
  so the form that forced 24000 is nowhere near 39 KB any more. The budget was
  left where it is because nothing measured it back up, not because 24000 is
  still the number the corpus needs -- re-measure with the procedure above before
  treating it as load-bearing.

The rosters in step 3 are the one thing still written by hand, so
`JvmRuntimeGroupNamesTest` pins each against what its builder actually emits, in
both directions. WASM has no equivalent gap: it emits the array builtins inline,
so there is no group to under-predict -- its `programUsesAnyArrayOp` only
excludes wrapper groups.

## The growth policy of `vector-push-extend` (`.todo/614`, 2026-08-31)

The capacity a full vector grows to is OBSERVABLE -- `array-dimension` reads it
back, and since `.todo/613` a sized sequence type tests it
(`(typep v (list 'string (array-dimension v 0)))`) -- so it is ONE policy for
all four backends even though CLHS leaves the default extension
implementation-dependent. It is stated once, in
`am.ik.rontolisp.ArrayGrowth`:

- a SUPPLIED `extension` is added to the capacity verbatim (`cap + ext`) -- the
  caller asked for that much room, so it gets exactly that much;
- with no extension the capacity DOUBLES (`GROWTH_FACTOR`), off a floor of
  `MIN_CAPACITY` = 1 for the zero-capacity vector, which doubling cannot grow;
- `NO_EXTENSION` = 0 is the "argument not supplied" sentinel, so ONE runtime
  entry point serves both arities. CLHS requires a supplied extension to be a
  positive integer, so a zero or negative one is undefined there and takes the
  default here.

Those are SBCL 2.2.9's numbers (measured 2026-08-31: capacity 2 reaches 8 after
five pushes, 0 reaches 1 after one and 8 after five, `ext` 100 on a capacity-2
vector reaches 102). Growing by one element per push -- what the compile paths
did before, by passing a literal `1` for the missing argument -- makes a push
loop quadratic in the number of pushes, and a push loop is what programs build
strings and buffers with.

The interpreter CALLS `ArrayGrowth.grownCapacity` from BOTH of its vector
representations (`LispArray.vectorPushExtend` for a general vector,
`LispString.vectorPushExtend` for the mutable character vector -- the latter
used to double from a floor of 8 on its own, which is how the disagreement
survived). Generated code cannot call the class, so the two compile paths EMIT
the same arithmetic inline against its constants
(`JvmArrayRuntimeBuilder`'s `_vectorPushExtend`, whose `ext` argument is the
sentinel when `JvmArrayCompiler` had no third argument to compile;
`WasmArrayCompiler.compileVectorPushExtend` + `emitDefaultGrownCapacity`).
Pinned by `LispEvaluatorTest.vectorPushExtendGrowthPolicyIsDoubling`, the
`compileVectorPushExtendGrowthPolicyIsDoubling` twins in `JvmLispCompilerTest`
and `WasmLispCompilerIntegrationTest`, and the
`vector-push-extend-growth-cross-backend` ci-spec case. The older
`fill-pointer-arrays-cross-backend` case never reads a dimension after a growth
run, which is why the divergence went unnoticed; leave the growth numbers in
the new case rather than folding them in.

Size cost of emitting the branchy formula instead of `cap + max(ext, 1)`,
measured 2026-08-31 on a three-call-site program (`collect` / `collect-ext` /
`collect-string`, one `vector-push-extend` each):

| output | before | after | delta |
| --- | ---: | ---: | ---: |
| JVM `.class` | 12011 | 12026 | +15 (once per program: one runtime helper) |
| WASM preview 1 | 14606 | 14674 | +68 (~23 per call site: emitted inline) |
| WASM component | 15777 | 15845 | +68 (same inline sites) |

The JVM cost is a constant per program; the WASM cost is per `vector-push-extend`
call site, because that backend has no runtime helper to share. ~23 bytes a site
buys linear-time growth, so it is not worth hoisting into a helper function.

## A slot the growth OPENS holds the element type's zero (`.todo/615`, 2026-08-31)

**Invariant: an array slot nobody wrote holds its array's remembered element
type's own zero, on all four backends, no matter WHICH operation opened it --
`make-array`'s allocation, `vector-push-extend`'s growth, or `adjust-array`'s.**
The growth policy above settles how far a vector grows; this settles what the
slots between the pushed element and the new capacity read back as. They are
below the DIMENSION, so `aref` may read them even though they are above the fill
pointer.

Before this the two compile paths opened them with a RAW NULL
(`JvmArrayRuntimeBuilder._vectorPushExtend`'s `list.add(null)`,
`WasmArrayCompiler.compileVectorPushExtend`'s `array.new` with a null init), so
a grown CHARACTER vector CRASHED there -- JVM `NullPointerException`, wasm
`cast failure` -- where the interpreter answered a character; and
`adjust-array`, whose expansion allocated a plain general array, filled with
`nil` on every backend including the interpreter, so a `double-float` vector
adjusted without `:initial-element` read back `NIL`. Not a `.todo/614`
regression: an explicit `extension` opened such slots before too; doubling only
made the default path reach them.

The fill is ONE question with ONE answer per element type
(`ArrayElementTypes.defaultElement`), asked of the ARRAY rather than of a
literal designator, which is what the new internal primitive
`%array-default-element` is:

- **Interpreter**: `Environment.arrayDefaultElement` switches on the
  representation (`LispString` -> the character zero, `LispIntVector` -> 0,
  `LispFloatArray` -> 0.0, `LispArray` -> `defaultElement(elementTypeCode())`).
  `LispArray.vectorPushExtend` already filled that way; `LispString` did not
  (its `int[]` buffer zero-filled to `#\Nul`) and now fills its grown and
  `adjust-array`ed slots with `ArrayElementTypes.DEFAULT_CHARACTER`. The
  `adjust-array` built-in defaults its `init` to the ARGUMENT's own zero and
  carries `elementTypeCode()` into the resized copy, so a non-adjustable
  adjustment keeps the declared type as well as the fill.
- **JVM**: `_arrayDefaultElement(o)` reads the SAME header facts
  `_arrayElementType` reads, in the same order -- a `String` argument is a
  character array; a length-4 header is the character-vector marker; a displaced
  or string-view header (slot 3 non-null) and a plain length-3 one remember
  nothing; otherwise header slot 4 holds the element type VALUE, an `Object[]`
  cons for `(unsigned-byte n)` and the name string otherwise.
  `_vectorPushExtend` calls it ONCE per growth and adds that instead of null.
- **wasm**: `compileArrayDefaultElement` mirrors `compileArrayElementType`'s
  four-representation dispatch, and `emitDefaultElementForHeader` reads the meta
  MARKER word. Marker 1 (the mutable character vector) is checked ALWAYS --
  no `make-array :element-type` scan can predict it, because any mutable string
  carries it -- and markers 2..7 are gated per width on `Ctx.typedArrayCodes`
  exactly as `emitRememberedElementType`'s arms are. `compileVectorPushExtend`
  emits the same helper as the `array.new` init.

`adjust-array` reaches it through its EXPANSION: `expandAdjustArray` now always
spells `:initial-element`, using `(%array-default-element a)` when the call gave
none. That keeps one implementation of the rule for the compile paths and the
interpreter's own built-in both.

**The character fill is `#\Space`, not SBCL's `#\Nul`** -- the one decision
this item had to make, and it is written where the fill rule lives
(`.kb/array-literals.md`, "The character fill is `#\Space`"). The general (`t`)
vector keeps `NIL` where SBCL answers `0`, unchanged and deliberate.

**Size cost, measured 2026-08-31** (`--optimize=size`, raw wasm; JVM `.class`):

| program | wasm before | wasm after | class before | class after |
| --- | ---: | ---: | ---: | ---: |
| one `vector-push-extend` site, general vector | 11,986 | 12,037 (+51) | 10,444 | 10,668 (+224) |
| one `adjust-array` site | 25,496 | 25,736 (+240) | 25,629 | 25,843 (+214) |
| `zlib` (`size-report/programs`) | 103,592 | 103,592 (+0) | -- | -- |
| `hello-clack` Worker (`--no-wasi`) | 382,481 | 382,481 (+0) | -- | -- |

The JVM bill is a constant per program (one runtime helper method); the wasm
bill is per SITE, because that backend has no runtime helper to share -- ~51
bytes for a push-extend site (the marker-1 arm plus the dispatch) and ~240 for
an `adjust-array` site (the full four-representation dispatch). A program that
uses neither operator compiles to the same bytes, which is why the two real
programs are exactly +0.

Pinned by `LispEvaluatorTest.aSlotOpenedByGrowthTakesTheElementTypeZero`, the
`compileASlotOpenedByGrowthTakesTheElementTypeZero` twins in
`JvmLispCompilerTest` and `WasmLispCompilerIntegrationTest`, and the
`opened-slot-fill-cross-backend` ci-spec case.

Closed by the section below: the fresh array a NON-adjustable adjustment answers
now remembers the declared element type on all four backends too.

## The adjusted COPY remembers the element type (`.todo/619`, 2026-09-02)

**Invariant: `adjust-array` never changes an array's element type, on all four
backends. An `:adjustable` array keeps its own identity and with it its type; a
NON-adjustable one answers a FRESH array that is STAMPED with the adjusted
array's type.** Before this the compile paths' expansion built that copy from a
plain `make-array` carrying `:initial-element`/`:fill-pointer`/`:adjustable` but
no `:element-type`, so an adjusted character vector came back a general vector:
`stringp` NIL, `array-element-type` T, where the interpreter and SBCL 2.2.9 both
answer T / CHARACTER.

**The stamp COPIES the type word; it does not re-derive the representation.**
That is the whole reason this is affordable. Spelling
`:element-type (array-element-type __adj_a)` on the expansion's `make-array` is
the obvious fix and `.todo/612`'s `lowerRuntimeElementTypeMakeArray` would even
serve it -- measured 2026-08-31 at **+4,264 bytes (+39%)** on a one-site program,
because a runtime designator turns one allocation into the seven-arm dispatch
(or a ~2.9 KB prelude helper), and because `Ctx.typedArrayCodes` would then have
to count every `adjust-array` program as every specialized width. The new
internal primitive `%array-adopt-element-type (new old)` moves the same
information one word at a time instead:

- **Interpreter**: `LispArray.adoptElementType`, the one writer of the
  `elementTypeCode` field. Its own `adjust-array` built-in already carried the
  code into the resized copy (`.todo/615`), so nothing changed there; the
  primitive exists so the expansion's spelling has an interpreter meaning too.
  `Environment.arrayElementTypeCode` is now the single per-representation
  answer, shared with `%array-default-element`.
- **JVM**: `_arrayAdoptElementType(dst, src)` reads the SAME header facts
  `_arrayDefaultElement` reads (a `String` or a length-4 header is `character`;
  otherwise slot 4 or nothing) and writes the stamp back in the SAME two shapes
  the allocator uses -- the length-4 character-vector marker for a RANK-1
  character type (over widened, boxed data, which the marker implies), header
  slot 4 otherwise, which a length-3 header grows to hold and a packed length-6
  one already has. A `dst` that is already a character vector, or displaced
  (slot 3 non-null, where slot 4 is the offset), keeps what it has.
- **wasm**: `compileArrayAdoptElementType` copies the meta MARKER word verbatim
  -- one `struct.set`, unconditional (writing a 0 marker onto a fresh array is
  what it already holds, and a guard costs more than it saves). Because the word
  is copied rather than decoded, there are NO per-code arms and therefore
  nothing for the per-width `Ctx.typedArrayCodes` gate to predict: the marker
  being copied was written by a `make-array` the same program already contains.
  `emitRememberedMarker` reads it, with `emitRememberedElementType`'s guards --
  the header cons whose car is the dims buckets is what tells an array from a
  hash table, and a displaced array's word is a real offset, so it remembers
  nothing.

**An IMMUTABLE string is now a legal `adjust-array` argument on the compile
paths.** `(adjust-array "abc" 5)` answered a 5-long string on the interpreter and
died in the expansion's primitives on both compile paths (JVM
`String cannot be cast to ArrayList`, wasm `cast failure`) -- and so did
`(array-dimensions "abc")`, `(array-rank "abc")`, `(array-total-size "abc")` and
`(array-displacement "abc")`, which is the same hole one level down: every shape
reader expands through `array-dimensions`. `_arrayDims` / `compileDims` now
answer a string's length in code points as its one dimension, and
`%array-disp-target` / `%array-disp-offset` answer nil / 0 for one (a string
VIEW is a header, never a runtime `String`), so all of them accept a string as
the interpreter's always have.

**What is still NOT adjustable** is a PACKED vector; the interpreter signals
`adjust-array: not applicable to a packed integer vector` / `... packed float
array` for both representations, and so does the JVM now (`_ivRequireGeneral` /
`_fvRequireGeneral`, both reached through `%array-disp-target`'s
`emitRequireGeneralIfPacked`, fixed 2026-09-02 by `.todo/627`; a program with no
packed float array emits no `_fvRequireGeneral` at all, so the default build
stays byte-identical). Wasm has no custom trap-message channel on this path for
EITHER representation -- `%array-disp-target`'s `castCellGet0` traps `cast
failure` on both a packed integer vector and a packed float array today, which
is the parity bar that backend can meet.

**Size cost, measured 2026-09-02** (`--optimize=size`, raw wasm; JVM `.class`):

| program | wasm before | wasm after | class before | class after |
| --- | ---: | ---: | ---: | ---: |
| one `adjust-array` site, character vector | 25,928 | 26,231 (+303, +1.2%) | 26,760 | 27,147 (+387, +1.4%) |
| `jzon` (`examples/asdf/jzon-demo.lisp`) | 444,002 | 445,311 (+1,309, +0.29%) | 526,686 | 527,082 (+396, +0.08%) |
| `cl-ppcre` (`examples/asdf/cl-ppcre-demo.lisp`) | 540,399 | 541,298 (+899, +0.17%) | 703,741 | 704,134 (+393, +0.07%) |
| `zlib` (`size-report/programs`, no `adjust-array`) | 103,592 | 103,652 (+60, +0.06%) | 161,640 | 161,671 (+31, +0.02%) |

**+303 where the keyword route measured +4,264**, i.e. a fourteenth of the price
for the same conformance. The wasm bill is per SITE (that backend has no runtime
helper to share); the JVM's is per PROGRAM, and the unused-helper prune keeps
`_arrayAdoptElementType` out of a program with no `adjust-array` entirely -- the
zlib row's +31/+60 is the STRING arm in the shape readers alone, which any array
program pays.

Pinned by `LispEvaluatorTest.anAdjustedCopyKeepsTheElementType`, the
`compileAnAdjustedCopyKeepsTheElementType` twins in `JvmLispCompilerTest` and
`WasmLispCompilerIntegrationTest`, and the
`adjusted-copy-element-type-cross-backend` ci-spec case -- one program, one
expected text, all four backends, every answer SBCL 2.2.9's but the two
deliberate deviations (`#\Space` for the character fill, `NIL` for the general
vector's).

## `sort`/`nreverse`/`stable-sort` keep a vector's fill pointer and identity (`.todo/623`, 2026-09-02)

**Invariant: `sort`, `nreverse` and `stable-sort` permute a fill-pointered or
adjustable vector/string IN PLACE -- same object, same fill pointer, same
adjustable flag, same total size -- on all four backends, matching SBCL.**
Before this, `seqResultDispatchForm`'s vector/string arm (shared by these three
and by the non-destructive `remove`/`substitute`/`reduce`/`remove-duplicates`
family) always answered a FRESH, SIMPLE rebuild: correct values, but a
fill-pointered array lost its fill pointer entirely --
`(let ((v (make-array 3 :adjustable t :fill-pointer 3 :initial-contents ...)))
(fill-pointer (sort v #'<)))` signalled `array has no fill pointer`, which is
what surfaced through `practicals-1.0.3/Chapter27/database.lisp`'s
`sort-rows`/`delete-rows` pair (`.kb/asdf.md`, "The _Practical Common Lisp_
book corpus" -- that chapter is byte-identical to SBCL since 2026-09-02).

**The fix is a second flag on `seqResultDispatchForm`, `destructive`, private to
the three permuting callers** (`wrapSortForStringSeq`, `wrapNreverseForStringSeq`,
`expandStableSort` -- `LispMacroExpander`, all three literal `true`). `false`
(`expandReduce`, `expandRemoveDuplicates`, `expandRemove`, `expandRemoveIf`,
`expandRemoveIfNot`, `expandSubstituteIf`) is unchanged: those five are
genuinely non-destructive by CLHS and by this codebase's own
`delete`/`delete-duplicates` precedent below, so their fresh, simple rebuild is
still correct and is NOT to be made identity-preserving.

**The destructive rebuild is `(replace __seq_in <fresh-rebuild>)` -- answering
THAT CALL's result, not `__seq_in` forced.** `replace`'s array arm always mutates
its target in place and returns it, so for a vector this is `__seq_in` itself
either way. For a STRING target it is not: a program-text string LITERAL cannot
be written in place (`.kb/string-write-runtime.md`), so `replace`'s string arm
copies it and answers the COPY. `(progn (replace __seq_in <rebuild>) __seq_in)`
-- answering the argument unconditionally -- was tried first and is wrong: it
silently discards that copy and answers the UNSORTED literal
(`(sort "cab" #'char<)` came back `"cab"`, caught by
`compileAndRunSequenceReturningFunctionsOnStrings` and
`compileSequenceOperatorsWithoutTheArrayRuntime` on the JVM suite). The
INTERPRETER takes a separate path, `Environment.seqResultDestructive`, since its
native `sort`/`stable-sort`/`nreverse` never routed through the macro expander's
dispatch: it writes `LispString`/`LispArray`/`LispIntVector` in place directly
(bypassing the `REPLACE` builtin, which cannot take a LIST source into a STRING
target -- `requireString` demands a literal `LispString`) and takes the same
source-literal branch (`sourceLiteral()` -> `copyForBulkWrite()`) `replace`'s own
target arm does.

**What this does NOT do: touch the backing store beyond the active length.**
`list`'s length is always exactly `original`'s own active length (fill pointer,
when present) for all three callers -- a pure permutation, never growing or
shrinking -- so a fill-pointered array's SPARE capacity (`array-total-size`
beyond `fill-pointer`) is untouched:
`(make-array 5 :adjustable t :fill-pointer 3 ...)` sorted keeps total-size 5,
fill-pointer 3, same object.

**`delete`/`delete-if`/`delete-if-not`/`nsubstitute`/`nsubstitute-if(-not)` are
the OTHER side of this bug, found by the same todo**: these five had NO
vector/string arm at all (only a cons-cell splice/`rplaca` loop), so a vector or
string argument was a SILENT NO-OP -- `(delete 1 (vector 3 1 2))` answered
`#(3 1 2)` unchanged. CLHS's "a destructive function may answer a fresh
sequence" latitude, and this codebase's own `delete-duplicates`-shares-
`remove-duplicates`'s-non-destructive-lowering precedent, make the CHEAP fix
also the RIGHT one: each routes through a RUNTIME check
(`LispMacroExpander.deleteOrSubstituteDispatch`, `(or (stringp seq) (vectorp
seq))`) to its `remove`/`substitute` family's own vector/string handling instead
-- a vector/string comes back a FRESH sequence like `remove`/`substitute` do
(NOT identity-preserved; that is deliberately out of scope here, since neither
CLHS nor any real caller found so far needs it, see the re-evaluation trigger
below). On the interpreter this reaches THREE separate code paths per operator
family, because `delete`/`nsubstitute` had raw `env.defineFunction` Java
closures (`Environment.removeValues`/`substituteValues`, shared with
`remove`/`substitute`), `delete-if`/`delete-if-not`/`nsubstitute-if(-not)` had
their own (`LispEvaluator.deleteIfValues`/`nsubstituteIfValues`, routed to
`removeIfValues`/`substituteIfValues`), and every one of them ALSO has an
`evalCons`/`compileCons` macro-expansion path for the direct-call and
`funcall`/`apply` (`BuiltinFunctionWrappers` wrapper-lambda) forms -- all had to
move together or `(delete-if ...)` and `(funcall #'delete-if ...)` would answer
differently again.

**Re-evaluation trigger**: if a caller ever needs
`delete`/`nsubstitute`-on-a-vector to be `eq` to its argument (this todo's
fill-pointer caller does not -- `sort-rows` calls `sort`, not `delete`, on the
vector; `delete-rows` calls `delete` on a LIST), the honest fix is in-place
compaction (shift surviving elements down, pull the fill pointer back) rather
than routing through `remove`, which is what SBCL does and what makes
`(eq v (delete item v))` true there for a fill-pointered `v`.

Pinned by `LispEvaluatorTest.evalSortNreverseStableSortKeepFillPointerAdjustableAndIdentity`
/ `evalDeleteNsubstituteFamilyOnVectorsAndStrings`, the
`compileAndRunSortNreverseStableSortKeepFillPointerAdjustableAndIdentity` /
`compileAndRunDeleteAndNsubstituteFamilyOnVectorsAndStrings` twins in
`JvmLispCompilerTest`, `sortNreverseStableSortKeepFillPointerAdjustableAndIdentity`
/ `deleteAndNsubstituteFamilyOnVectorsAndStrings` in
`WasmLispCompilerIntegrationTest`, and the
`sort-nreverse-stable-sort-keep-fill-pointer-adjustable-and-identity` /
`delete-and-nsubstitute-family-on-vectors-and-strings` `ci-spec.yaml` cases (all
four backends, byte-identical).

## adjust-array

`(adjust-array array new-dims &key initial-element fill-pointer)` on every
backend: elements are preserved at the subscripts valid in BOTH shapes
(per-subscript, not flat -- resizing a matrix keeps `(i, j)` at `(i, j)`); an
`:adjustable` array is adjusted IN PLACE and returned itself (`eq`), otherwise a
fresh array is returned; without an explicit `:fill-pointer` the old fill
pointer carries over (make-array range-checks it against the new size, so
shrinking below it errors like CL); rank mismatch signals a clear error;
`:displaced-to` AS A KEYWORD to `adjust-array` itself is rejected (CLHS
territory rontolisp does not implement), but a DISPLACED ARGUMENT is legal --
it un-displaces first, matching SBCL 2.2.9 (`.todo/657`, below). Without an explicit
`:initial-element` the opened cells take the array's own element type zero, not
`nil`, and the result carries the argument's element type unchanged (the two
sections above). An IMMUTABLE string is a legal argument everywhere (it answers a
fresh 5-long string); a PACKED vector is not.

Implementation split, chosen to keep the n-dimensional copy logic OUT of
per-backend codegen:

- **Interpreter**: a real `Environment` built-in (`adjustArray` in
  `Environment.java`, runtime keyword parsing) over `LispArray.become(other)`
  (replaces dims/data/fillPointer in place, keeps the adjustable flag).
- **Compile path**: `LispMacroExpander.expandAdjustArray` -- a Lisp-level
  expansion over existing primitives (`make-array` with the carried-over
  `:fill-pointer`/`:adjustable`, `array-dimensions`, `array-total-size`,
  two-arg `floor` for the subscript decomposition, `row-major-aref` /
  `%row-major-aset` for the copy) plus ONE new internal primitive
  `%array-become` per backend, plus `%array-default-element` for the fill and
  `%array-adopt-element-type` for the copy's declared type (JVM
  `_arrayBecome`: header dims/fp copy +
  ArrayList resize/copy; WASM: three inline `struct.set`s swapping the header's
  dims car, meta fp and data slot). Both compilers dispatch
  `ADJUST_ARRAY -> compileExpr(expandAdjustArray(cons))`.

## Displacement (`:displaced-to`)

Lite semantics on every backend: a displaced array cannot be combined with
`:initial-element`/`:initial-contents` (compile-time
`UnsupportedOperationException` on JVM/WASM since make-array keywords are
literal; runtime error on the interpreter), and `:displaced-index-offset`
requires `:displaced-to`. It CAN itself be adjusted with `adjust-array` --
it un-displaces first (`.todo/657`, below). It MAY carry a fill pointer
and the `:adjustable` flag -- the section below. The view is bounds-checked
at creation (`total + offset <= target total`, target dims product). Chains
(view of view) resolve transitively, and access follows the CURRENT storage of
each hop, so a view keeps aliasing an adjustable target after
`vector-push-extend`/`adjust-array` grow it in place -- pinned on all backends
(`displacedArraySeesTheTargetGrowInPlace` / `compileDisplacedArrays` / the
`adjust-displaced-arrays-cross-backend` ci-spec case). Rank may differ from the
target's (vector view over a matrix row).

`array-displacement` returns target + offset as TWO values via the syntactic
multiple-value tier: `isMvProducerForm`/`lowerMvProducer` recognize
`(array-displacement x)` and read the two internal accessors
`%array-disp-target` / `%array-disp-offset` over one temp; in an ordinary
context `expandArrayDisplacement` yields the primary
`(%array-disp-target x)`. No `%mv-spill` involvement.

Semantics shared by all backends: only rank-1 arrays may have a fill pointer
(`:fill-pointer t` = the vector size, an integer = that value, range-checked);
the fill pointer is the effective length (`length`, `#(...)` printing, the
sequence view) while `aref`/`row-major-aref` still reach the full backing
store; `vector-push-extend` grows any fill-pointer vector regardless of
`:adjustable` (the flag is reported verbatim by `adjustable-array-p`);
`array-element-type` always returns `t` (`:element-type` is parsed and
ignored). `%set-fill-pointer` is the internal `setf` target wired in
`LispMacroExpander.expandSetf`. On the compile path `array-element-type`
expands to `(progn <array> t)` (`LispMacroExpander.expandArrayElementType`).

First-class values: `#'vector-push` etc. work via
`BuiltinFunctionWrappers.ARRAY_FILL_POINTER_FUNCTIONS` (fill-pointer,
array-has-fill-pointer-p, adjustable-array-p, array-element-type, vector-push,
vector-pop, vector-push-extend -- the last in its 2-arg form -- plus
make-array), gated like `HASH_FUNCTIONS`: both compilers inject the group only
when `programUsesAnyArrayOp` (which also gates the JVM array helpers and now
lists the fill-pointer names) is true, so the wrappers and their helpers stay
gated together. `#'make-array` is a variadic wrapper (`variadicMakeArray`)
whose runtime keywords are re-extracted with `getf`: a `:displaced-to`
argument selects the bare-view shape, everything else the general
`:adjustable`/`:fill-pointer`/`:initial-element` shape, and `:element-type` is
accepted and ignored like the call position -- this is what makes
cl-utilities' verbatim `copy-array` (`apply #'make-array (list* dims
options...)`) work on the compile path.

Interpreter/JVM errors carry `fn: message` text (e.g. "vector-pop: empty
vector"); WASM traps (`unreachable`) on the same conditions. Compiled `list`
argument evaluation is right-to-left (.todo/014), so tests/ci-spec sequence
side-effecting pushes/pops through separate bindings.

## Displacing a STRING (`.todo/544`, 2026-08-28)

`(make-array n :element-type 'character :displaced-to s :displaced-index-offset k)`
where `s` is a STRING answers a **string view**: `stringp`, `length` n, the
target's characters from k on, printing/`string=`/`subseq`/`char` all seeing the
slice, `array-displacement` reporting the target and k -- and NO copy. Views
chain, and each hop is resolved at access time like an array view's.

**The TARGET decides the shape, not `:element-type`.** The portable substring
idiom -- cl-ppcre's `nsubseq`, and with it every `regex-replace` with a FUNCTION
replacement and every `:sharedp t` entry point -- passes the target's own
`(array-element-type sequence)`, which is a RUNTIME value, so a rule keyed on
the element type would not fire for the one caller that matters. Both compilers
therefore keep the `:displaced-to` branch AHEAD of the runtime-element-type
lowering, and the shape decision happens at run time inside the displacement
helper. That whole surface signalled `MAKE-ARRAY expects an array` until this,
and `eval/ClPpcreSharedSubseq` rewrote `nsubseq` to copy; the rewrite is retired
and the verbatim definition runs on all four backends (`ClPpcreE2eTest` exercises
it). Answering a COPY from `make-array` was explicitly not the fix, since it
would make every other library's displacement silently stop aliasing.

**Writing through a view reaches the target's storage where the backend has
one.** The interpreter's strings are all mutable, so a write always writes
through. On the compiled backends the mutable CHARACTER VECTOR is that storage,
and since 2026-08-31 (`.todo/559` step 2) a `copy-seq`/`subseq` result IS one
(`.kb/string-write-runtime.md`, "A copy-seq/subseq result is mutable with
identity") -- so a view over the common allocated-string case writes THROUGH to
its target on all four backends, pinned by
`compileDisplacedStringViewOverACopySeqResultWritesThrough` (JVM + WASM, the
rewritten form of the promote-on-write tests 559 planted to fail when it
landed). What remains immutable is a LITERAL (and the other producers'
results -- `concatenate`, `string-upcase`, `format nil` -- until their own
flip): such a view is still built without a copy and READS through it, and the
first write PROMOTES -- the view's target slot is replaced by a character
vector holding the same characters, and the store lands there; the view is a
mutable string from then on, `array-displacement` reports the promoted vector,
and the original string value is untouched, exactly as `(setf (char s i) c)` on
that same string re-binds rather than writes (`LispMacroExpander
.expandScharSetFunctional`).

Per backend:

- **Interpreter** -- `LispString` gained `displacedTo`/`displacedOffset`/
  `viewLength` beside the fill-pointer fields; `storage()`/`base()` walk the
  chain and every read/write (`value`, `charAt`, `setCharAt`, `capacity`,
  `length`, `replaceInPlace`) goes through them. A view has no fill pointer and
  is not adjustable, so `setFillPointer`/`vectorPushExtend`/`adjustCapacity`
  reject it. `Environment`'s `make-array` returns the view when the
  `:displaced-to` argument is a `LispString` (rank-1 only, bounds-checked
  against the target's capacity), and `%array-disp-target`/`%array-disp-offset`
  accept a `LispString`.
- **JVM** -- the header-LENGTH tag gained **7 = displaced string view**
  (`{dims, null, null, target, offsetLong, null, null}`), chosen at view
  creation by `_arrayMakeDisplaced` when the target is a `String` or an array
  whose own header is a character vector (4) or a string view (7); a String
  target is bounds-checked with `_scount`. `stringp`'s length-4 arm accepts 7
  too, and `_strv` renders a 7-header by resolving the chain: a character-vector
  target renders element by element from the resolved base, a String target in
  ONE `substring` between two `_cpoff` translations. `emitResolveDisplacement`
  ends the walk on a non-ArrayList target with the offset already folded in, so
  one test (`header.length > 4 && header[3] != null`) tells `_rmGet`/`_rmSet`
  they landed on a string: `_rmGet` reads `_cpoff` + `codePointAt` and boxes
  `int[]{cp}`, `_rmSet` promotes through the new `_strToCharVec` helper (in
  `METHOD_NAMES`, so it lives and dies with the array gate).
- **WASM** -- no new heap type and no new `FUNC_*` index. A string view stores
  the TARGET STRING in the header's data slot, where an array view stores the
  target cell, which makes the shape self-describing. `WasmArrayRuntimeBuilder
  .emitResolve` now leaves the final header in its cursor local (rather than
  pushing the buckets array) and folds in this view's own offset when the data
  slot is a `TYPE_STRING`; `_arr_get` then reads it with `_str_char_at` and
  boxes a `TYPE_CHAR`, and `_arr_set` builds the promoted character-vector cell
  INLINE and `struct.set`s it into the `(meta . data)` cons. Offsets stay in
  CHARACTER units on both sides, so the byte-offset question the todo raised
  never arises -- the three shared accessors already translate. `_charvec_p`
  answers true for a string data slot and recurses one hop for a cell one;
  `_charvec_to_str` reads the element through `_arr_get` when the data slot is
  not a buckets array (one `ref.test` per character against the whole UTF-8
  encode); `%array-disp-target`/`%array-disp-offset` treat a string data slot as
  a target.

## A displaced view is not a BARE view (`.todo/647`, 2026-09-02)

**Invariant: `make-array :displaced-to` accepts `:fill-pointer` and
`:adjustable`, on all four backends, and the fill pointer is the VIEW's own.**
CLHS forbids only `:initial-element` / `:initial-contents` beside
`:displaced-to` (the view owns no storage to initialize); `:fill-pointer` and
`:adjustable` are explicitly allowed, and "a growing view over a bigger backing
store" is an ordinary CL idiom. All four backends used to refuse the three
together with one message, so two thirds of it was not a CL rule.

The item was filed as a REPRESENTATION problem -- "the displaced shape and the
header shape are alternatives per backend" -- and the measurement overturned
that premise: **both header layouts already had the slots**. A JVM displaced
header is `{dims, fp, adj, target, offsetLong}` (7 elements for a string view)
with slots 1/2 simply written null, and a wasm header's meta chain is
`(fp . (adj . offset))` with the same two written null. So the make-array half
was one argument pair threaded through `_arrayMakeDisplaced` /
`compileMakeDisplaced` (both reusing the existing fill-pointer resolution --
the JVM's extracted `emitResolveFillPointer`, wasm's shared `_arr_fp` -- against
the VIEW's own element count, never the target's), plus the interpreter's
`LispArray`/`LispString` view constructors. No header length moved, no marker
changed, and every reader that decides "displaced?" by header LENGTH or by the
data slot is untouched.

What DID need work is the three operations that reach the data:

- **`vector-push` / `vector-pop` write and read THROUGH.** Both used to touch
  the array's own storage directly (`ArrayList.set(1 + fp)` on the JVM, the
  header's data slot on wasm, `this.data[fp]` in `LispArray`), which a view does
  not have. They now go through the displacement-aware element primitives that
  every `aref`/`aset` already used -- JVM `_rmGet`/`_rmSet`, wasm's shared
  `_arr_get`/`_arr_set` (a CALL, which is why routing them through it made the
  wasm sites SMALLER, not larger), interpreter `readFlat`/`writeFlat`. A string
  view's push writes into the target string exactly as `(setf (char v i) c)`
  does, promotion on an immutable target included.
- **A full view UN-DISPLACES when it grows.** `vector-push-extend` past the
  view's span copies the current contents into storage of its own and drops the
  displacement, so the growth never runs off the end of someone else's array;
  `array-displacement` answers nil/0 from then on and further pushes no longer
  touch the target. That is SBCL 2.2.9's behavior, measured 2026-09-02 (a
  capacity-4 view with fill pointer 2 grows to total size 8 and reports
  `(NIL 0)`, and the pushes that still FIT are visible in the target). One
  implementation per backend: `LispArray.undisplace` / `LispString.undisplace`,
  JVM `_arrayUndisplace` (in `METHOD_NAMES`, so it lives and dies with the array
  gate), wasm `_arr_undisplace` (`FUNC_ARR_UNDISPLACE`, appended after
  `FUNC_ARR_CHECK_RANK` so no index above shifts, reusing
  `TYPE_CALLABLE_BASE + 0`).
- **The shape survives the un-displacement.** The JVM's header LENGTH is the
  tag, so 7 (a displaced STRING view) becomes 4 (the character-vector marker)
  and 5 becomes 3; wasm's meta OFFSET word doubles as the element-type marker,
  so the resolved target's marker is copied into it (1 when the chain ends on a
  string) -- the same "copy the word, do not re-derive the representation" move
  `%array-adopt-element-type` makes. A grown string view is still `stringp`.

`_strv` (JVM) also learned the view's fill pointer: a length-7 header used to
render its whole dimension, and the shared `emitActiveLength` now answers "fill
pointer when present, else dims[0]" for the character-vector arm and the string
view alike.

`#'make-array`'s variadic wrapper carries the two keywords into its displaced
branch too, so `(apply #'make-array (list n :displaced-to b :fill-pointer 2))`
agrees with the call position.

Diffed against SBCL 2.2.9 on 2026-09-02: every answer identical -- lengths,
dimensions, total size, `aref` past the fill pointer, printing, the pushed
target contents, the un-displaced capacity, `array-displacement` before and
after, and the string view's characters -- EXCEPT `adjustable-array-p` over a
view, which is the pre-existing recorded divergence above (SBCL answers `T` for
any non-simple array; rontolisp reports the `:adjustable` argument verbatim).

**Cross-backend gap found 2026-09-02, `.todo/658`**: when the array being
un-displaced is a GENERAL array that REMEMBERS a non-packable element type
(`.todo/619`), wasm's `_arr_undisplace` correctly chain-resolves and carries
that type across (its meta offset word doubles as the marker, and
`emitRememberedMarker` walks to the chain's end), while the interpreter's
`LispArray.undisplace()` and the JVM's `_arrayUndisplace` both silently drop
it back to `t` -- `array-element-type` answers `(UNSIGNED-BYTE 8)` on wasm,
`T` on the other two, for the SAME source. SBCL is not a usable oracle here
(it rejects the underlying `:displaced-to` construction outright, since CL
requires element-type compatibility rontolisp's lite displacement does not
check). Unmeasured whether fixing it is worth the blast radius; `.todo/658`
holds the plan.

**`adjust-array` on a displaced array un-displaces it first (`.todo/657`,
2026-09-02), matching SBCL 2.2.9.** `expandAdjustArray`'s old displaced-check
arm (which signaled "adjust-array: displaced arrays are not supported") is
replaced by an unconditional call to `%array-undisplace`, a new internal
primitive that is a thin wire onto the un-displace machinery `.todo/647` had
already built for `vector-push-extend`'s growth (`LispArray.undisplace` /
`LispString.undisplace`, JVM `_arrayUndisplace`, wasm `_arr_undisplace`): the
argument's current view contents become its own storage and the displacement
drops, in place, BEFORE the rest of the adjustment runs -- unconditionally, not
only when the array is `:adjustable`, so the answer is the same on every
backend regardless of which branch (in-place `%array-become` vs. a fresh copy)
the rest of the expansion takes. The interpreter's own `adjust-array` built-in
(`Environment.adjustArray`) calls `array.undisplace()` at the same point for
the same reason; the `LispString` arm's `str.adjustCapacity` already called it
(`.todo/647`), so only its own now-redundant `displacedTo() != null` refusal
needed to drop.

Diffed against SBCL 2.2.9 on 2026-09-02: an `:adjustable` displaced argument
is adjusted IN PLACE (`eq` to the argument), keeps the elements at the
subscripts valid in both shapes, and comes back un-displaced
(`array-displacement` answers `NIL, 0`) -- identical. A NON-adjustable
displaced argument diverges from SBCL by the SAME pre-existing rule every
other non-adjustable `adjust-array` argument already does (SBCL treats a
displaced array as "not simple" and adjusts it in place regardless of
`:adjustable`, per the `adjustable-array-p` divergence recorded above;
rontolisp tracks `:adjustable` verbatim and answers a fresh array instead).
CLHS leaves further use of the OLD array unspecified once it is not
`:adjustable`, so the un-displace runs on it too rather than being skipped --
its `array-displacement` answers `NIL, 0` from then on, same as the fresh
copy's.

Pinned by `LispEvaluatorTest.adjustArrayUndisplacesADisplacedArgument`, the
`compileAdjustArrayUndisplacesADisplacedArgument` twin in
`JvmLispCompilerTest` and `WasmLispCompilerIntegrationTest`, and the
`adjust-array-undisplaces-cross-backend` ci-spec case (all four backends,
byte-identical) -- beside the pre-existing coverage:
`LispEvaluatorTest`'s `aDisplacedViewCarriesItsOwnFillPointerAndAdjustableFlag`
/ `aFullDisplacedViewUndisplacesWhenItGrows`, the
`compileADisplacedViewCarriesItsOwnFillPointerAndAdjustableFlag` /
`compileAFullDisplacedViewUndisplacesWhenItGrows` twins in
`JvmLispCompilerTest`, the same two names in
`WasmLispCompilerIntegrationTest`, and the
`displaced-fill-pointer-cross-backend` ci-spec case (all four backends,
byte-identical).

Wiring points for `%array-undisplace`: `LispNames.ARRAY_UNDISPLACE`
(`%ARRAY-UNDISPLACE`), the `CL_INTERNALS` entry in `PackageRegistry`, the
`usesGeneralArrayOp` gate list in `LispMacroExpander` (the shared
"programUsesAnyArrayOp" list both compilers read), and a case in
`Jvm`/`WasmExprCompiler.compileCons` calling `JvmArrayCompiler
.compileArrayUndisplace` / `WasmArrayCompiler.compileArrayUndisplace` -- both
thin wrappers over the existing `_arrayUndisplace` / `_arr_undisplace`
runtime helpers. No separate `Environment` registration: `adjust-array` is
not expanded through `LispMacroExpander` on the interpreter (it has its own
real built-in), so `%array-undisplace` is compile-path-only.

`expandAdjustArray` calls `%array-undisplace` UNCONDITIONALLY (not gated by
`%array-disp-target`) and, critically, as PART of `a`'s own `let*` binding
(`(a (%array-undisplace <array-expr>))`), not as a later body statement:
every OTHER binding that reads `a` -- `od` (`array-dimensions`), and above
all `newArr`'s `%array-adopt-element-type` stamp -- must see the
ALREADY-undisplaced state, because a still-displaced array's marker/offset
word is genuinely ambiguous on the compile backends (the single word doubles
as the offset while displaced and only becomes the resolved element-type
marker once `_arr_undisplace`/`_arrayUndisplace` runs); reading it one
binding too early silently answers "remembers nothing" instead of the
chain-resolved type. This surfaced as a real regression during development,
not a hypothetical: with the undisplace call placed as a separate body
statement (running after all bindings), `compileAnAdjustedCopyKeepsTheElementType`
crashed with `ClassCastException: String cannot be cast to ArrayList` --
because `%array-undisplace` runs on EVERY `adjust-array` argument
unconditionally, including a literal immutable string, which has no header at
all. Both `_arrayUndisplace` (JVM) and `_arr_undisplace`'s wasm call site
(`WasmArrayCompiler.compileArrayUndisplace`) now open with the same
`instanceof`/`ref.test` TYPE_STRING check `_arrayDispTarget`/
`compileDispTarget` already had, answering the string unchanged before
reaching the header/cell cast. A packed integer or float array has no such
guard and is unaffected: the JVM call site (`compileUnary`, shared with
`compileDispTarget`/`compileDispOffset`) already runs
`emitRequireGeneralIfPacked` first, so it gets the same clear "not applicable
to a packed integer vector"/"...packed float array" text `adjust-array`
already gave through the old `%array-disp-target` probe
(`JvmLispCompilerTest.compilePackedFloatArrayRejectsAdjustArray`); wasm has
no such guard on this primitive (as before) and traps on the cell cast, the
existing parity bar
(`WasmLispCompilerIntegrationTest.compileAdjustArrayTrapsOnAPackedFloatArray`).

Still deliberately NOT supported: `adjust-array` on a packed integer vector or
a packed float array, a different decision covered above ("What is still NOT
supported").

## Representation

### Interpreter (`LispArray`)

`LispArray` has mutable state (fields are no longer `final`):

- `int fillPointer` -- the fill pointer, or `-1` for none. When present it is
  the effective length; only rank-1 arrays may have one.
- `boolean adjustable` -- the `:adjustable` flag (verbatim; reported by
  `adjustable-array-p`).

`data` / `dimensions` are non-final so `vector-push-extend` can reallocate. Key
methods: `effectiveLength()` (fill pointer if present, else `data.length`),
`vectorPush` (returns the index or `-1` when full), `vectorPop`,
`vectorPushExtend` (grows `data` + `dimensions[0]` when full), `setFillPointer`.
`render()` (the `#(...)` printer) iterates `effectiveLength()`, so a
fill-pointer vector prints only up to the fill pointer. `length` (in
`Environment`) uses `effectiveLength()`.

`aref` / `row-major-aref` still reach the FULL backing store (CL semantics: the
fill pointer bounds the sequence view, not element access).

That holds for STRINGS too, and the interpreter was the odd one out until
2026-07-26: `Environment.charRef` (which backs `char` / `schar` / `aref` on a
string) and `%schar-set` bounded the index by `LispString.length()`, i.e. the
fill pointer, so `(aref s 5)` on a `:fill-pointer 3` string of capacity 8
signalled on the interpreter and returned the inactive slot on all three compile
backends. Both now bound by `capacity()`. CL is explicit that `char`, `schar` and
`aref` ignore fill pointers ("it is permissible to use `aref` to access any array
element, whether active or not"), so the compile backends were right and the
divergence was silent -- no test covered an inactive string slot. Pinned now by
the `string-fill-pointer-inactive-slots` ci-spec case (read AND write through
`aref`, all four backends byte-identical).

**`char` / `schar` past the fill pointer are still three-way divergent, and the
ci-spec case deliberately does not cover them**: the interpreter now returns the
slot, the JVM throws a raw `String.offsetByCodePoints` exception, and both WASM
backends return `#\Nul` (they materialize the string at its fill-pointer length
and have no bounds check at all -- writing past the end silently APPENDS there).
That is the general missing-bounds-check gap, not a fill-pointer question;
`.todo/186` holds it with the measurements.

Displacement: `displacedTo` (a `LispArray` or null) + `displacedOffset` fields,
both NON-final since `.todo/647` (`undisplace()` clears them in place);
a displaced array's `data` is a shared empty array and all element access goes
through `readFlat`/`writeFlat`, which walk the chain adding each hop's offset
(so growth of the target's storage is followed -- the chain holds the OBJECT,
not the storage). `totalSize()` (dims product) replaced `data.length` in every
bounds check; `effectiveLength()` = fp or `totalSize()`. `become(other)`
replaces dims/data/fillPointer in place (adjust-array's adjustable half).

`make-array` parses `:fill-pointer` (`t` = size, integer = that value, `nil` =
none; rank-1 only), `:adjustable`, and ignores unknown keywords such as
`:element-type`. New builtins live in `Environment.registerArrays`.

### JVM (implemented)

An array is still a `java.util.ArrayList`, but slot 0 now holds a header
`Object[]{dims, fillPointer, adjustable}` -- `dims` the `Object[]` of Long
dimension sizes (length = rank), `fillPointer` a `Long` or null, `adjustable`
the RAW `:adjustable` argument (null = nil; kept verbatim so
`_adjustableArrayP` is a null test). Since todo-611 a header may carry a fourth
fact in SLOT 4, the REMEMBERED element type (`_arrayMakeTyped`, read by
`_arrayElementType`); slot 4 is free on every non-displaced array because slot 3
-- the displacement target -- is what says whether it holds an offset instead, so
the ordinary header grows to length 5 for it and no length TAG below moved
(`.kb/array-literals.md`, "The degraded array REMEMBERS its element type"). Data
stays at slots `1..` (the `1 + flat`
offset in `_aref*`/`_aset*` is untouched); every dims reader gained one extra
`aaload 0` (`emitFlat2`, `emitFlatN`, `_arrayDims`, `buildToString`,
`JvmLengthRuntimeBuilder`). BOTH header producers build the wrapper:
`_arrayMake` (signature grew to `(dims, init, fillPointer, adjustable)`;
`JvmArrayCompiler.compileMake` compiles the keyword value expressions or pushes
null) and `JvmQuoteCompiler.compileQuotedArray` (literals: slots 1/2 null).
`buildToString` + `_length` clamp the element count to the fill pointer.

**A PLAIN general array starts PACKED (todo-527, 2026-08-26).** When
`_arrayMake` sees no fill pointer, no adjustability and an initial element that
is nil or an in-range integer (a runtime decision, so `#'make-array`'s variadic
wrapper and `adjust-array`'s temp allocation take it too), the ArrayList holds
ONLY a LENGTH-6 header `Object[]{dims, null, null, null, null, long[] data}`
and the row-major elements live unboxed in the `long[]`, with `Long.MIN_VALUE`
as the nil sentinel (storing that integer itself widens instead, so it stays
representable). A random `aref` into a large general vector of integers is then
ONE probe into one flat primitive array -- the layout SBCL's simple-vector of
immediate fixnums has -- instead of a dependent pointer chase through an 8 MB
`Object[]` and a scatter of boxed `Long`s; that chase was linear in the array
size (1.7 -> 55.5 ns/access from 10^3 to 10^6 elements) and is now flat the way
SBCL's is (~3 -> ~12 ns), taking `.todo/517`'s top-level `aref` row from 1.22 s
to 0.51 s against SBCL's 0.26-0.29. The mechanics:

- The tag is the header LENGTH: 3 = ordinary boxed, 4 = character vector,
  5 = displaced OR ordinary boxed WITH a remembered element type (told apart by
  slot 3, exactly as they always were), **6 = packed** (slot 4 free for the
  remembered element type, so packing survives it), 7 = displaced STRING VIEW. `emitResolveDisplacement`'s loop already ends
  on the null `header[3]`, so a packed header never reads as a displacement,
  and `_strv`/`stringp`'s `length == 4` test never sees it as a character
  vector. `_arrayDispOffset` gained a `header[3] != null` test (length > 4
  alone would have answered null instead of 0 for a packed array).
- `_rmGet`/`_rmSet` -- the single data-access primitives every accessor and the
  printer already funnel through -- branch on the tag AFTER the displacement
  walk (the header is already in a local, so the ordinary path pays one
  `arraylength` compare): packed reads `data[idx-1]` (sentinel -> nil), packed
  stores write an in-range `Long` unboxed.
- The first store that cannot pack (a non-integer, or the sentinel integer)
  calls **`_arrayWiden`**: the header is replaced by a length-5
  `{dims, null, null, null, et}` -- slot 4 carried over, so a widened array still
  answers the element type it was asked for -- and every `long[]` element is
  appended boxed (sentinel as null). The
  ArrayList object IS the array's identity, so every alias sees the widened
  array -- widening is invisible, one O(n) copy, once. `_arrayBecome` widens
  both operands up front (its element copy is `size()`-based) and
  `_charVecMake` widens before stamping the length-4 marker.
- The one non-helper consumer of the representation, `JavaBridgeTemplate
  .marshal` (the `java:` interop sequence bridge), got its own packed branch.
  A SECOND one arrived with `.todo/534`: the JVM integer-fusion pass reads the
  shape raw inside a fused tree (`JvmIntFusionCompiler`'s aref leaf,
  `.kb/jvm-int-fusion.md`), re-testing the same tag -- ArrayList, `size() != 0`,
  `get(0) instanceof Object[]`, `length == 6` -- and bailing into `_aref1` for
  everything else, so it must move with any change to what the tag means.
- The fill-pointer surface (`_vectorPush`/`Pop`/`PushExtend`,
  `_fillPointer`/`_setFillPointer`) never meets a packed array: packing
  requires fp == null, and those helpers error on a missing fill pointer
  first. The packed INT vectors' `long[]{width, ...}` values are disjoint --
  the array VALUE here is still the ArrayList, `instanceof long[]` on it is
  false, so the iv -> fv -> general dispatch chains are untouched.

Pinned by `JvmLispCompilerTest.compilePackedGeneralArrayWidensInvisiblyOnANon
IntegerStore` / `compilePackedGeneralArrayNilAndSentinelInteger` /
`compilePackedGeneralArrayRankNAndDisplacedView` plus the whole pre-existing
fill-pointer/adjust/displaced/copy-array set. The interpreter and both WASM
backends keep their boxed general representation (the wasm general `aref`'s
cost is size-INdependent dispatch, a different defect -- `.todo/517`'s residual
note).

A DISPLACED array carries a 5-element header
`Object[]{dims, fp, adj, target, offsetLong}` (7 elements when the target is
a string, see "Displacing a STRING"; slots 1/2 are null unless the view was
given `:fill-pointer`/`:adjustable`) and holds NO data slots
(`_arrayMakeDisplaced(dims, target, offset)`, bounds-checked against the
target's dims product). Every data access now funnels through the two
displacement-aware primitives `_rmGet(list, idx1based)` / `_rmSet(...)`: a loop
`while header.length > 3 && header[3] != null { idx += header[4]; list =
header[3] }` (the offset composes with the 1-based list index directly), then
`get/set(1+flat)`. `_aref1/2/N`, `_aset1/2/N` and `buildToString`'s element
read end in `invokestatic _rmGet/_rmSet` (the builders take the generated
class's `selfClass` for the methodref); `buildToString`'s no-fp element count
and `_length`'s no-fp fallback switched from `size() - 1` to the dims product /
`dims[0]` (same value for ordinary arrays, correct for displaced).
`_arrayDispTarget`/`_arrayDispOffset` read header slots 3/4;
`_arrayBecome(a, b)` copies dims+fp into a's header and resizes/copies the
ArrayList elements in place.

New static helpers in `JvmArrayRuntimeBuilder` (same array gate):
`_fillPointer`, `_setFillPointer`, `_arrayHasFillPointer`, `_adjustableArrayP`,
`_vectorPush`, `_vectorPop`, `_vectorPushExtend` (push-extend appends nulls to
the ArrayList and updates the inner dims[0]), `_rmGet`, `_rmSet`,
`_arrayMakeDisplaced`, `_arrayBecome`, `_arrayDispTarget`, `_arrayDispOffset`.
Call sites are wired in `JvmArrayCompiler` + `JvmExprCompiler.compileCons`;
`--optimize` (`JvmClassShaker`) keeps used helpers via the ordinary
invokestatic call graph (verified with `--optimize` on fill-pointer and
displaced/adjust-array programs).

### WASM (implemented)

The `TYPE_CELL` box now holds a header `TYPE_CONS` of
`(dims . (meta . data))`: `dims`/`data` are the same `TYPE_HASH_BUCKETS`
arrays; `meta` is `(fillPointer-i31-or-null . (adjustableRaw . offset-i31))`
(the offset is 0 for an ordinary array). The header's CAR is still the dims
buckets array, so the array-vs-hash-table discriminator used by `%arrayp` /
`WasmLengthCompiler` / the printer is unchanged, and
`compileDims`/`emitFlatIndex` (car readers) needed no change. Producers:
`compileMake` (resolves `:fill-pointer` at runtime -- null / bounds-checked
i31 / `t` -> dims[0] -- only when the keyword appears at the call site) and
`WasmQuoteCompiler.compileQuotedArray` (meta `(null . (null . 0))`).

A DISPLACED array stores the TARGET CELL in the data slot (instead of a
buckets array) and its offset in the meta chain
(`compileMakeDisplaced`; bounds-checked against the target's dims product,
traps when too small). Every data access site
(`compileAref`/`compileAset`/`compileRowMajorAref`/`compileRowMajorAset`) runs
the inline `emitResolveDataAndIndex` walk: while the data slot `ref.test`s as
`TYPE_CELL`, add the meta offset to the flat index and hop to the target's
header -- an ordinary array falls straight through. Because the walk re-reads
each hop's CURRENT header, a view keeps aliasing a target grown in place by
push-extend/adjust-array. The printer (`WasmRuntimeBuilder.emitPrintArray`)
gained the same walk (one extra i32 local, `baseSlot`, in
`buildPrintValBody`/`buildPrincValBody`) and its no-fp element count switched
from the data-buckets length to the dims product; `WasmLengthCompiler` already
used `dims[0]` and needed nothing. `compileAdjustableArrayP` reads
`meta.cdr.car`; `%array-become` is three inline `struct.set`s;
`%array-disp-target`/`%array-disp-offset` read the data slot (cell or nil) and
`meta.cdr.cdr`. The vector-push family DOES see displacement since `.todo/647`
(a view may carry a fill pointer): its element access is `_arr_get`/`_arr_set`
and its growth calls `_arr_undisplace` first.

The builtins are emitted INLINE in `WasmArrayCompiler`
(compileFillPointer/SetFillPointer/HasFillPointer/AdjustableArrayP/VectorPush/
VectorPop/VectorPushExtend/ArrayBecome/DispTarget/DispOffset; push-extend
copies into a fresh buckets array with a loop and `struct.set`s the inner cons
+ dims[0]) -- no new heap type, no new `FUNC_*` index, so the component blobs
are untouched and Preview 1 / `--component` stay identical.

### `--no-gc` scalar WASM

Unsupported, like every array operation on the scalar backend: the eligibility
scan (`NoGcWasmCompiler.collectCallsCons`) names the operation in a clear
compile error ("--no-gc: unsupported operation 'vector-push' in function 'f'
..."). Documented under the `--no-gc` section of `doc/*/compiling/wasm.md`
("vectors" in the ineligible list).

## Wiring points (adjust-array / displacement)

`LispNames`: `ADJUST_ARRAY`, `ARRAY_DISPLACEMENT`, `ARRAY_BECOME`
(`%array-become`), `ARRAY_DEFAULT_ELEMENT` (`%array-default-element`),
`ARRAY_DISP_TARGET`/`ARRAY_DISP_OFFSET`
(`%array-disp-target`/`-offset`), `DISPLACED_TO_KEYWORD`,
`DISPLACED_INDEX_OFFSET_KEYWORD`. `PackageRegistry`: the two public names in
`CL_FUNCTIONS`, the four `%`-names in
`CL_INTERNALS`. Both compilers' `programUsesAnyArrayOp` gates list all six
names. `BuiltinFunctionWrappers`: `binary(ADJUST_ARRAY)` (2-arg form) +
`unary(ARRAY_DISPLACEMENT)` (primary value only) in the
`ARRAY_FILL_POINTER_FUNCTIONS` group. `--no-gc` rejects the new names through
its default unknown-operation error.

## Tests / docs

- Interpreter: `LispEvaluatorTest` -- `fillPointerLengthAndAccessors`,
  `fillPointerVectorPrintsUpToFillPointer`, `vectorPushStoresAndReturnsIndexOrNil`,
  `vectorPushThenReadBack`, `vectorPop`, `vectorPushExtendGrowsBeyondCapacity`,
  `vectorPushExtendGrowthPolicyIsDoubling`,
  `aSlotOpenedByGrowthTakesTheElementTypeZero`,
  `setfFillPointer`, `simpleVectorHasNoFillPointer`,
  `fillPointerOnNonFillPointerVectorSignals`, `clUtilitiesCopyArrayRunsOnInterpreter`,
  `adjustArray*`, `displacedArray*`, `arrayDisplacementReturnsTargetAndOffset`,
  `makeArrayDisplacedErrors`, `displacedStringView*`,
  `aDisplacedViewCarriesItsOwnFillPointerAndAdjustableFlag`,
  `aFullDisplacedViewUndisplacesWhenItGrows`.
- JVM: `JvmLispCompilerTest.compileFillPointer*` / `compileVectorP*` /
  `compileSetfFillPointer` / `compileSimpleVectorHasNoFillPointer` /
  `compileFillPointerFirstClassWrappers` / `compileClUtilitiesCopyArray` /
  `compileAdjustArray` / `compileDisplacedArrays` /
  `compileArrayDisplacementValues` /
  `compileMakeArrayDisplacedKeywordComboIsACompileError` /
  `compileDisplacedStringView` /
  `compileDisplacedStringViewOverAnImmutableStringPromotesOnWrite` /
  `compileVectorPushExtendGrowthPolicyIsDoubling` /
  `compileASlotOpenedByGrowthTakesTheElementTypeZero`.
- WASM: the same set in `WasmLispCompilerIntegrationTest`.
- E2E: ci-spec `fill-pointer-arrays-cross-backend` +
  `vector-push-extend-growth-cross-backend` +
  `opened-slot-fill-cross-backend` +
  `adjust-displaced-arrays-cross-backend` +
  `displaced-string-views-cross-backend` +
  `displaced-fill-pointer-cross-backend` (all four backends), and the
  shared-substring lines of `ClPpcreE2eTest`'s exercise (the verbatim
  `nsubseq`).
- Docs: `reference/functions/{fill-pointer,array-has-fill-pointer-p,
  adjustable-array-p,array-element-type,vector-push,vector-pop,
  vector-push-extend,adjust-array,array-displacement}.md` (en+ja) + the
  make-array page + the functions table.
