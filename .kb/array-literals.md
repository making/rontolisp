# An array literal is a CONSTRUCTOR, not a constant

**Invariant: every evaluation of an array literal -- `#(...)`, `#nA(...)`, `#*1011`,
`#f(...)`, `#d(...)`, `#N@(...)` -- answers a FRESH, independently mutable array, on
all four backends. Two evaluations of one literal are never `eq`, and a write through
one is invisible to the next.** Pinned by the `array-literal-freshness-cross-backend`
`ci-spec.yaml` case and by
`LispEvaluatorTest.everyArrayLiteralSyntaxIsFreshAtEveryEvaluation` /
`#writingThroughAnArrayLiteralDoesNotReachTheNextEvaluation` /
`#anArrayNestedInAnArrayLiteralIsFreshToo`.

This deviates from Common Lisp, deliberately. CLHS leaves the consequences of modifying
a literal undefined and permits coalescing, so a real CL answers `t` to
`(eq (f) (f))` for `(defun f () #(1 2 3))`. Here a literal behaves like `(vector 1 2 3)`:
the reader's array is the SOURCE constant and never leaves the program.

## Why not "shared and immutable" (measured 2026-08-29)

The conformant alternative -- one shared array per literal, a write signalling an error
-- was costed before this landed, and every measurement pointed the other way.

**The tree had already committed to mutable literals, in four places.** Existing pinned
behavior that "shared and immutable" would have had to delete:

| pinned today | where |
|---|---|
| `(let ((m #2A((1 2) (3 4)))) (setf (aref m 0 1) 9) m)` -> `#2A((1 9) (3 4))` | `LispEvaluatorTest.rank2ArrayLiteralIsMutable` (the test is NAMED for it) |
| the same shape for `#d(...)` and `#f(...)` | `ci-spec.yaml` `packed-float-*` / `packed-single-float-*` |
| `(let ((s "abc")) (setf (elt s 0) #\z) (print s))` -> `"zbc"` on all four | `ci-spec.yaml` `setf-elt-cross-backend` |

A probe that marked every evaluated array literal read-only and threw on a write failed
exactly **6 tests in 3 classes** out of 214 test classes (`rank2ArrayLiteralIsMutable`,
`packedIntVectorReaderLiteralAndRowMajor`, three in `LispFloatArrayTest`, and
`LinalgSimdTest.theSelectsAndCopiesAreBitIdenticalToTheScalarOracleAtEveryShapeAndWidth`)
-- small, but every one of them is a deliberate pin, not an accident.

**The shipped Lisp and the examples do not need it.**
`src/main/resources/am/ik/rontolisp/eval/*.lisp` contains ZERO array literals in code
(the seven `grep` hits are all in comments). `examples/` has 28 literal sites and not one
of them writes through a literal -- `nn.lisp` and `nn-vec.lisp` say so in a comment
("They are never mutated"), and `webgl-battlefront`'s `#f(...)` `defvar`s are only ever
`setq`ed to a fresh `linalg:` result. So the immutable reading had no program to protect;
it only had programs to break.

**The premise the todo argued from was false.** `.todo/578` proposed the immutable
reading as "consistent with how string literals already behave here". Measured, a string
literal is NOT immutable here: it is shared (`(eq (fs) (fs))` is `t` on all four) and a
write to one succeeds. The tree's real, pinned rule for a literal write is "it is legal
and it is local to the binding you wrote through", which is what fresh-per-evaluation
gives exactly and coalescing gives never. (When this was written the interpreter did not
keep the second half of that rule -- its write corrupted the source constant while the
compile paths rebuilt per binding. `.todo/580` closed that on 2026-08-29 by giving the
interpreter the same rebind, so the string half now holds on all four:
`.kb/string-write-runtime.md`, "A string LITERAL is never written". The conclusion below
is unaffected -- it rests on the rule, not on which backends implemented it.)

**`PureBuiltinFolder` rests on freshness.** `.kb/pure-builtin-fold.md` admits a packed
integer-vector result into the fold *because* "both compile backends allocate the array
and fill it AT THE SITE ... two evaluations of one folded table are two independently
mutable vectors", and `WasmQuoteCompiler.compileIntVectorLiteral`'s own comment calls
that "the property the fold rests on". Sharing literals would make the fold unsound and
would have to be paid for by deleting it.

**The enforcement half is not affordable.** Sharing alone is cheap on the JVM (a third
static-field pool beside `JvmLispCompiler.LayoutPool` / `BigIntPool`) and new-but-bounded
on wasm (a global initializer; the `_t_sym` lazy build and the rawSentinel `struct.new`
init expression are the two existing shapes). Erroring on a WRITE is what costs, because
neither backend has a store choke point or a spare bit:

- JVM packed `float[]`/`double[]` (`[rank, dims..., data...]`) has **no free slot**. The
  rank word is read as `(int) a[0]` in ~160 places, most of them inside
  `JvmSimdVectorTemplate` / `JvmGpuTemplate` / `JvmBlasTemplate` -- pre-compiled Java
  template classes whose bytecode is embedded whole into the output, so any header
  re-encoding means editing three template classes in lockstep.
- Stores bypass `_fvAset1/2/N` in `JvmTypedLoopCompiler` (raw `FASTORE`/`DASTORE`),
  `JvmIoRuntimeBuilder`'s bulk `_readSeqPacked`, and ~35 in-place SIMD/GPU kernels.
- On wasm a packed integer vector is a BARE `(array (mut i8|i16|i32))` with no header at
  all, so a flag needs a wrapper struct that relocates every site that touches one; and
  every emitted type is `sub final` (`.kb/wasm-gc-final-types.md`), so subtype tagging is
  off the table. There is no `_aset` choke point either: `WasmVecLoops.arraySet` alone is
  called from 11 sites in `WasmLinalgSimdRuntimeBuilder`.

So the immutable reading costs a header re-encoding in the three hottest emitters on both
compile backends, buys nothing any program in the tree wants, deletes an optimization, and
breaks six pinned tests. Fresh-per-evaluation costs one interpreter copy.

## What actually changed

Only the INTERPRETER moved. Both compile backends already rebuilt the literal at the
site (`JvmQuoteCompiler.compileQuotedArray` / `compilePackedLiteral` /
`compileSinglePackedLiteral` / `compileLiteralIntVector`;
`WasmQuoteCompiler.compileQuotedArray` / `compilePackedLiteral` /
`compileSinglePackedLiteral` / `compileIntVectorLiteral`), so no backend code was
touched and no existing ci-spec expectation moved.

`eval/LiteralArrays.materialize` is the interpreter's half, called from the three
self-evaluating array arms of `LispEvaluator.eval`. The copy is **deep through nested
ARRAYS only** -- `#(#(1 2) #(3 4))` yields fresh inner vectors, matching
`compileQuotedVal`'s recursion -- and passes every other element (a number, a string, a
symbol, a cons) through by identity.

Cost, measured on the interpreter: a `(setq *x* #f(1.0 2.0 3.0))` loop runs at ~600 ns an
iteration, of which the added `float[3]` + `int[1]` copy is tens of nanoseconds.
Interpretation dominates; the allocation is noise. On the compile backends nothing
changed, because nothing there was shared to begin with.

## What `quote` does instead (settled 2026-08-30)

`'#(1 2 3)` is **not** covered, by design: the same syntax under `quote` is a
CONSTANT, one shared object per quote site on ALL FOUR backends -- for a cons as much
as for an array. The freshness rule of this file is about literals OUTSIDE quote, and
the two rules meet exactly at the `'`. The quoted-datum topic (the decision, the
per-backend memoization, the shaker measurement that made the JVM's lazy, and the
pinning tests) is `.kb/quoted-data.md`; `evalQuote` still hands the datum back as is,
because `(quote <value>)` is also the interpreter's live-value splice
(`LispEvaluator.quoteValue`), and since `.todo/579` both compile backends memoize the
datum instead of rebuilding it.

**The one literal family that goes the other way is an INSTANCE.** A bare `#P"..."` /
`#S(...)` in code position is a CONSTANT, not a constructor: one shared object per site
on all four backends since `.todo/581` (`.kb/quoted-data.md`, "A BARE instance literal
shares the same slot"). The rule of this file did not lose a case -- the two are decided
by the same question and it has different answers. An array literal reaches the
interpreter through three arms of `LispEvaluator.eval` that carry NOTHING but literals,
so materializing there was free; an instance reaches it through the `LispInstance` arm,
which also carries every live instance the evaluator splices back through
`(quote <value>)` and cannot tell the two apart. The interpreter could be moved for an
array and cannot be for an instance, so the compile side meets it instead.

## Where to look when this changes

- `eval/LiteralArrays` -- the interpreter's materialization.
- `LispEvaluator.eval`'s `LispArray` / `LispFloatArray` / `LispIntVector` arms.
- `ci-spec.yaml` `array-literal-freshness-cross-backend` -- the four-backend pin.
- `.kb/pure-builtin-fold.md` -- the fold that depends on this invariant.
- `doc/{en,ja}/reference/data-types.md` -- the user-facing statement.

## The RANK-0 array, and why `#0A` carries no parens (2026-08-31)

`(make-array nil)` is a legal array on all four backends: no dimensions, a total size of
1 (the empty product), and one element that `aref` / `(setf (aref ...))` reach with **no
subscripts at all**. It is not a special case bolted onto the model but the empty case of
the model already in place -- the flat index is the Horner fold over the subscripts, and
the fold over zero subscripts is 0. Every fold in the tree therefore starts at `flat = 0`
and runs from `k = 0`, never at `subs[0]` with `k = 1`: `LispArray.flatIndex`,
`LispFloatArray.flatIndex`, `JvmArrayRuntimeBuilder.emitFlatN` and the two
`JvmFloatArrayRuntimeBuilder` N-bodies. On the compile backends the subscript count is
STATIC at the site, so `JvmArrayCompiler`/`WasmArrayCompiler`'s `compileAref` and
`compileAset` rewrite the rank-0 shape to an explicit index 0 before emitting; the
runtime folds above are the definition those sites agree with, not a live path.

Why it exists beyond conformance: a rank-0 array is CL's box for "a scalar seen as an
array", and generic array libraries lean on it -- array-operations' `as-array` default
method IS `(make-array nil :initial-element object)`, so without it `(aops:dims 1)` and
the 0-dimensional-object arm of `stack-rows`/`stack-cols` fail (`.kb/asdf.md`).

**The syntax is `#0A<datum>`, with no parens anywhere.** `#0A5` holds the number 5 and
`#0A(1 2)` holds the LIST `(1 2)` -- the datum after `#0A` is read whole, which is why
`LispLexer` accepts `#0A` without the `(` every other rank requires and `LispReader.readArray`
takes one `readExpr()` for rank 0. The printers mirror it exactly: `LispArray.renderArrayData`
returns early for rank 0, the JVM's `_arrayToString` appends `"0A"` in place of the `"("`
and skips the closing paren, and wasm's `emitPrintArray` tests rank 0 OUTSIDE the packed
branch (so the `#d(`/`#f(` prefixes never apply to one) and suppresses its `rparen` the same
way. That last point is also why wasm's string table holds `"A"` and `"("` separately
instead of one `"A("`.

A rank-0 array prints `#0A<datum>` at EVERY representation, packed float included: the
JVM's packed prefix rewrite is a `^#\d*A?\(` regex that simply does not match a
paren-less rendering, and the interpreter's `renderArrayData` ignores the caller's
`openPrefix` for rank 0. So `(make-array nil :element-type 'double-float)` prints
`#0A0.0` and reads back as a general rank-0 array -- the round trip loses the packing,
which nothing depends on and SBCL spells the same way.

`--no-gc` is the one backend that refuses: it has no general array type and its packed
kinds carry a static rank, so `NoGcWasmCompiler.dimExprs` answers an empty list for `nil`
and `compileMakeArray` reports "a rank-0 make-array ... is not supported", the same shape
as its existing rank >= 3 refusal.

Pinned by the ci-spec case `rank-zero-arrays-cross-backend`,
`LispEvaluatorTest.makeArrayWithNoDimensionsIsARankZeroArray` /
`#rankZeroArrayIsWrittenAndPrintedWithoutSubscripts` /
`#rankZeroArrayLiteralReadsItsDatumWhole`,
`LispReaderTest.readRank0ArrayLiteralHoldsOneDatumWithoutParens`,
`JvmLispCompilerTest.compileAndRunRankZeroArray` and
`WasmLispCompilerIntegrationTest.compileRankZeroArray`.

The two gaps this left were closed the same day, in `.kb/declarations-type-checks.md`:
`type-of` builds the compound array specifier (todo-604, so a rank-0 array answers
`(SIMPLE-ARRAY T NIL)`), and the atomic `vector` spellings check the rank (todo-605, so
`vectorp` of a rank-0 or rank-2 array is `NIL`).

## A SPECIALIZED element type above rank 1 is the general array (2026-08-31)

**Invariant: `:element-type` selects a specialized representation only at RANK 1, except
the packed FLOAT families, which are packed at every rank. Above rank 1 a `character` or
`(unsigned-byte 8|16|32)` request answers the PLAIN GENERAL array on all four backends --
`stringp` and `vectorp` are `NIL`, `array-element-type` is `T`, `type-of` is
`(SIMPLE-ARRAY T dims)` -- and the only trace the element type leaves is the fill for
unsupplied elements.**

Half of that rule was already the tree's (`.kb/packed-integer-vectors.md`: "rank-n ...
keeps the general boxed representation", `_ivMake`'s runtime rank check). The CHARACTER
half was rank-BLIND until todo-607, and each backend read the same program differently:

```lisp
(let ((b (make-array '(2 2) :element-type 'character :initial-element #\a)))
  (list (stringp b) (array-element-type b) (array-dimensions b) (type-of b)))
; interpreter   (NIL       T         (2 2) (SIMPLE-ARRAY T (2 2)))
; wasm (both)   (T         CHARACTER (2 2) STRING)
; JVM           error: make-array: :fill-pointer requires a rank-1 array
; SBCL 2.2.9    (NIL       CHARACTER (2 2) (SIMPLE-ARRAY CHARACTER (2 2)))
```

**Why degrade rather than mark.** The character MARKER does not mean "the elements are
characters" -- it means "this general array IS a string", and a string is a rank-1
character array and nothing else. Extending it above rank 1 would be a second, different
fact ("the declared element type is character"), and the general array carries no
element-type field on ANY of the three representations: the JVM's marker IS the header
length (4), wasm's is one i31 meta slot (0 or 1), and `LispArray` has no such field at
all. Worse, every consumer of the marker is a string operation that indexes linearly --
`_strv` / `_charvec_to_str` / `_charvec_p` and the ~30 call sites that normalize through
them -- so marking a rank-2 array means teaching all of them a rank they have no reason
to read. Degrading instead makes the marker's implication explicit and enforced at the
ONE constructor: **the marker implies rank 1**, so no reader checks the rank.

The answer that costs is `array-element-type`, which says `T` where SBCL says
`CHARACTER`. That is not a character special case: it is exactly what a rank-2
`(unsigned-byte 8)` array already answers (SBCL: `(UNSIGNED-BYTE 8)`). Giving the general
array a remembered element type is one change covering both, and it is `.todo/611`, not
this item.

**What each backend does.** The rank is a RUNTIME fact at every site (the dimensions are
an expression), so all three compile-time recognizers stayed and the rank test moved into
the allocation:

- **Interpreter** (`Environment.makeArrayBuiltin`): the character arm already required
  `dims.length == 1`; only the general path's default element moved (below).
- **JVM** (`JvmArrayRuntimeBuilder`, `_charVecMake`): opens with `_ivMake`'s exact rank-1
  test -- `dims` is a `Long`, or an `Object[]` cons whose cdr is not one -- and rank n
  returns `_arrayMake(dims, init, null, adj)` without the length-4 header. The old
  failure was upstream of that: `JvmArrayCompiler.compileMake` defaulted the fill pointer
  by re-compiling the DIMENSIONS expression, which `_arrayMake` then rejected for rank 2
  with a message about a keyword the program never passed. The default is now the
  unspelled `t` designator, which `_arrayMake` already resolves to the vector size -- so
  the dims expression is also **evaluated exactly once** now (it was evaluated twice on
  this backend, and once on the other three).
- **wasm** (`WasmArrayCompiler.compileMake`): the meta-offset marker is
  `array.len(dims) == 1` instead of a compile-time `1`, emitted only under the
  compile-time character branch (a program with no character `make-array` is
  byte-identical).
- **The `:initial-contents` character lowering**
  (`LispMacroExpander.lowerCharacterInitialContentsMakeArray`, the compile paths' "answer
  a fresh string copy of the contents" shortcut) declines a LITERAL rank >= 2 dims list,
  so that call falls to `lowerInitialContentsMakeArray`'s nested row-major fill over a
  general array. A dims expression whose rank is only known at run time keeps the rank-1
  reading, which is the rank the general lowering assumes there too.

**The one trace the element type leaves is the fill.** An unsupplied element of a
degraded array is one OF THE DECLARED TYPE, not `nil`: `#\Space` for a rank-n character
array (the same fill the rank-1 string gets) and `0.0` for a packed float type that fell
back for a fill pointer or adjustability. CL leaves the value of an uninitialized element
undefined, and an array the program asked to hold characters or floats should hold them
even where its type tag cannot say so. Both defaults now live in ONE place per backend --
`Environment.makeArrayBuiltin`'s general path on the interpreter, which is what todo-607
added: the float half had been defaulted on the three COMPILE backends only, so
`(aref (make-array 3 :element-type 'double-float :adjustable t) 0)` answered `NIL` here
and `0.0` there.

The packed integer widths do NOT keep their `0` under the same degrade (a rank-2
`(unsigned-byte 8)` array reads `NIL`), because unlike the other two they have no
fallback default anywhere yet; that is the other half of `.todo/611`.

Pinned by `LispEvaluatorTest.evalCharacterElementTypeAboveRankOneIsAGeneralArray` /
`#evalMakeArrayEvaluatesItsDimensionsExactlyOnce`,
`JvmLispCompilerTest.compileCharacterElementTypeAboveRankOneIsAGeneralArray` /
`#compileMakeArrayEvaluatesItsDimensionsExactlyOnce`, their
`WasmLispCompilerIntegrationTest` twins, and the `character-element-type-above-rank-one`
ci-spec case -- one program, one expected text, all four backends.
