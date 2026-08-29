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

## What `quote` still shares

`'#(1 2 3)` is **not** covered: `evalQuote` hands the datum back as is, so on the
interpreter two evaluations of one quoted array literal are `eq` while both compile
backends rebuild it.

That is deliberate and it is `quote`'s constraint, not this one. At run time the
interpreter also uses `(quote <value>)` to splice a LIVE value back into a form for
re-evaluation -- `LispEvaluator.quoteValue`, four sites, one of them
`evalSequenceWithGrayDispatch`'s rebuild. Materializing in `evalQuote` was implemented,
and it broke `read-sequence` outright: the destructive fill landed in a copy and every
`read-sequence`/`write-sequence` test read back its initial contents. There are ~15 more
`(quote <value>)` constructions in `eval` and `macro`, so the splice pattern cannot be
audited cheaply.

A quoted CONS has exactly the same divergence and always has -- measured 2026-08-29:

```lisp
(defun ql () '(1 2 3))
(eq (ql) (ql))                       ; interpreter T, JVM/WASM NIL
(let ((a (ql))) (setf (car a) 99))
(ql)                                 ; interpreter (99 2 3), JVM/WASM (1 2 3)
```

So the quoted-datum question is one topic covering conses and arrays together, and it
moves as one. Pinned as it stands by
`LispEvaluatorTest.aQuotedArrayIsStillTheDatumOnTheInterpreter`.

## Where to look when this changes

- `eval/LiteralArrays` -- the interpreter's materialization.
- `LispEvaluator.eval`'s `LispArray` / `LispFloatArray` / `LispIntVector` arms.
- `ci-spec.yaml` `array-literal-freshness-cross-backend` -- the four-backend pin.
- `.kb/pure-builtin-fold.md` -- the fold that depends on this invariant.
- `doc/{en,ja}/reference/data-types.md` -- the user-facing statement.
