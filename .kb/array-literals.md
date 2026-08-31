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

What is still missing, and belongs to `.todo/604` rather than here: `type-of` answers `T`
for a rank-0 array where CL says `(SIMPLE-ARRAY T NIL)`, and `vectorp` answers `T` for
every array regardless of rank (a pre-existing gap that rank 2 already had).
