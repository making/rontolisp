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
`stringp` and `vectorp` are `NIL` -- and the only traces the element type leaves are the
fill for unsupplied elements and the type the array REMEMBERS (the section below; when
this was written it left the fill alone and `array-element-type` answered `T`).**

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

The answer that cost was `array-element-type`, which said `T` where SBCL says
`CHARACTER`. That was not a character special case: it was exactly what a rank-2
`(unsigned-byte 8)` array answered too. Giving the general array a remembered element
type was one change covering both, and it is the section below.

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

**The first trace the element type leaves is the fill.** An unsupplied element of a
degraded array is one OF THE DECLARED TYPE, not `nil`: `#\Space` for a rank-n character
array (the same fill the rank-1 string gets), `0` for a packed integer width and `0.0`
for a packed float type that fell back for a fill pointer or adjustability. CL leaves the
value of an uninitialized element undefined, and an array the program asked to hold
characters, bytes or floats should hold them even where its representation cannot say so.
All three defaults live in ONE place per backend, keyed by the element type CODE
(`ArrayElementTypes.defaultElement`) rather than by three ad-hoc tests -- what todo-607
started for the character and float halves (the float half had been defaulted on the
three COMPILE backends only, so
`(aref (make-array 3 :element-type 'double-float :adjustable t) 0)` answered `NIL` here
and `0.0` there) and todo-611 finished for the integer widths.

**The character fill is `#\Space`, everywhere a slot is opened, and that is a DECISION
(2026-08-31).** CLHS leaves an uninitialized element's value undefined, so the value is
the project's to pick, and it is picked ONCE -- `ArrayElementTypes.DEFAULT_CHARACTER`,
which `defaultElement` reads and which every backend's opened-slot fill spells. SBCL
2.2.9 answers `#\Space` for `make-string` and `#\Nul` for the slots
`vector-push-extend` / `adjust-array` open, i.e. it makes the value depend on WHICH
operation opened the slot. One rule for the whole surface is worth more than matching
SBCL on a value the standard does not pin, so a grown character vector reads back
`#\Space` here and `#\Nul` there. The general (`t`) vector keeps `NIL` where SBCL
answers `0`, for the same reason in the other direction: `NIL` is this project's answer
for element type `t` and it is already what every backend gives.

Pinned by `LispEvaluatorTest.evalCharacterElementTypeAboveRankOneIsAGeneralArray` /
`#evalMakeArrayEvaluatesItsDimensionsExactlyOnce`,
`JvmLispCompilerTest.compileCharacterElementTypeAboveRankOneIsAGeneralArray` /
`#compileMakeArrayEvaluatesItsDimensionsExactlyOnce`, their
`WasmLispCompilerIntegrationTest` twins, and the `character-element-type-above-rank-one`
ci-spec case -- one program, one expected text, all four backends.

## The degraded array REMEMBERS its element type (2026-08-31)

**Invariant: a general array carries the UPGRADED element type it was asked for, on all
four backends. `array-element-type` answers it, `type-of` builds `(SIMPLE-ARRAY et dims)`
/ `(VECTOR et size)` from it, `typep` takes the same specifier back, and an unsupplied
element takes that type's own zero. The representation degrades; the declared type does
not.** So the program at the head of the section above now answers SBCL 2.2.9's text
verbatim, and so does the rank-2 `(unsigned-byte 8)` array that motivated it:

```lisp
(let ((a (make-array '(2 2) :element-type '(unsigned-byte 8))))
  (list (array-element-type a) (type-of a) (aref a 0 0)))
; all four, and SBCL 2.2.9   ((UNSIGNED-BYTE 8) (SIMPLE-ARRAY (UNSIGNED-BYTE 8) (2 2)) 0)
```

**The type space is CLOSED, so it is a code, not a value.** `make-array` upgrades an
element type to exactly seven answers -- `t`, `character`, `(unsigned-byte 8|16|32)`,
`single-float`, `double-float` -- because those are the representations that exist;
everything else (`fixnum`, `integer`, `bit`, a class, an unsupported `(unsigned-byte 4)`)
upgrades to `t` and is remembered as nothing at all. `am.ik.rontolisp.ArrayElementTypes`
is that space: `codeOf` (the one recognizer, quote-unwrapping and
package-qualifier-stripping like the per-backend ones it replaced), `valueOf` (the value
`array-element-type` answers) and `defaultElement` (the fill). It lives in the ROOT
package so `LispArray`, `Environment`, `LispMacroExpander` and both codegen packages can
share it.

**Each backend spends a slot it already had, so no array grew.** This is what made the
change affordable, and it is why the three representations do not agree on the encoding:

- **Interpreter**: one `int` field on `LispArray`. `become` (adjust-array's in-place half)
  does not touch it, so the type survives adjustment, and `vectorPushExtend` fills the
  slots it opens with `defaultElement` rather than nil. `adoptElementType` is its ONE
  writer after construction -- the fresh copy a non-adjustable `adjust-array` answers
  takes the adjusted array's stamp (`%array-adopt-element-type`,
  `.kb/adjustable-arrays.md`).
- **JVM**: header slot **4**, which is free on every non-displaced array -- slot 3 (the
  displacement target) is what says whether slot 4 holds an offset instead. The ordinary
  length-3 header grows to 5 (`{dims, fp, adj, null, et}`), and the length-6 PACKED header
  ALREADY HAS the slot (`{dims, null, null, null, et, long[] data}`), so a remembered
  element type never costs `.todo/527`'s packing -- a rank-2 `(unsigned-byte 8)` matrix is
  still one flat `long[]`. No existing header-length test moved: 4 is still the character
  vector, 6 still packed, 7 still the string view, and `header[3] != null` still means
  displaced. `_arrayMakeTyped(dims, init, fp, adj, code)` builds the value once at
  allocation and stamps it; `_arrayElementType` reads it back; `_arrayWiden` carries slot
  4 into the widened header; `_ivMake` and `_charVecMake` stamp it on their rank-n
  fallbacks, which is where the rank -- a RUNTIME fact -- is finally known.
- **wasm**: the meta MARKER word, `meta.cdr.cdr`, which held only 0 (plain) or 1 (mutable
  character vector) and is read as an offset only on a displaced array (whose data slot
  holds a target cell). The remembered type is `code + 1`, i.e. 2..7, leaving 1 to the one
  shape that is a string rather than a general array carrying a type -- so `_charvec_p`,
  the owner of that invariant, is untouched, and `%array-disp-offset`'s existing
  "non-displaced arrays report 0" guard already covers the new values.

**The general arm of `array-element-type` is GATED, and gated per WIDTH.**
`LispMacroExpander.makeArrayElementTypeCodes(program, registry)` scans the program's
`make-array` calls for element types that upgrade to something other than `t` and answers
a bit MASK. A program with no such call compiles byte-identically; a program that asks for
one width emits the arm for that width alone. The scan is deliberately coarse -- a rank-1
request that never degrades counts too, because the rank is a run-time fact at most call
sites. On wasm the mask rides in `Ctx.typedArrayCodes` and **must be copied in
`WasmAsyncEmit.freshCtx`**, which builds the synchronous top level's chunks: without it a
top-level `(array-element-type a)` answers `t` while the same form inside a defun answers
the remembered type, which is exactly how this was found.

**`type-of` had to ask the simplicity question FIRST**, because a typed array can have a
fill pointer: `(make-array 4 :element-type 'double-float :fill-pointer 0)` is
`(VECTOR DOUBLE-FLOAT 4)`, not a `simple-array`. The prelude arm order is now
fill-pointer/adjustable, then the `t`-and-rank-1 `simple-vector` case, then
`(simple-array et dims)`. That reorder needed `array-has-fill-pointer-p` and
`adjustable-array-p` to answer **NIL** for a packed array instead of refusing it -- which
is what CL says of a simple array and what the interpreter had always answered, while the
JVM threw "not applicable to a packed integer vector" and wasm trapped on a `ref.cast`.
One divergence closed on the way past.

**The size cost, measured 2026-08-31** (`--optimize=size`, gzip -9):

| artifact | raw before | raw after | gzip before | gzip after |
|---|---|---|---|---|
| `hello-clack` Worker (`--no-wasi`) | 377,352 | 378,137 (+785, +0.21%) | 114,047 | 114,482 (+435, +0.38%) |
| `zlib` (`size-report/programs`) | 103,158 | 103,592 (+434, +0.42%) | -- | -- |

Both programs are IN the gate -- babel/uax and chipz ask for `(unsigned-byte 8)` -- so
these are the paying rows, not the free ones. The bill is the width arm at each
`array-element-type` site plus the marker word at each typed `make-array`, and it is the
same order as the `vectorp` rank check's +0.44% (`.kb/declarations-type-checks.md`). A
program outside the gate pays nothing.

Pinned by `LispEvaluatorTest.evalGeneralArrayRemembersItsDeclaredElementType`,
`JvmLispCompilerTest.compileGeneralArrayRemembersItsDeclaredElementType`,
`WasmLispCompilerIntegrationTest.compileGeneralArrayRemembersItsDeclaredElementType` and
the `general-array-remembers-its-element-type` ci-spec case -- one program, one expected
text, all four backends, every answer SBCL 2.2.9's.

**What still answers `t`.** A DISPLACED view answers `t` on all four, on purpose: its meta
slot carries the offset, not a type, and SBCL answers `t` for a view whose own
`:element-type` was unstated too.

**What CARRIES the remembered type across an operation** is the same one word per backend,
and `adjust-array` copies it rather than re-deriving it -- the measurement that settled
that, and the seven-arm alternative it beat by fourteen times, are in
`.kb/adjustable-arrays.md`, "The adjusted COPY remembers the element type".

## A RUNTIME `:element-type` reaches the same array a literal one does (2026-08-31)

**Invariant: `(make-array n :element-type et)` with `et` a VALUE builds what the literal
spelling of that value would build, on all four backends -- the representation, the
remembered element type and the zero fill.** The interpreter has had the designator in
hand all along; the compile backends decide every representation from the LITERAL at the
call site, so the designator has to be turned back into one. Before todo-612 the lowering
branched on `character` alone and dropped the keyword otherwise, so
`(aref (make-array 4 :element-type et) 0)` with `et` bound to `'(unsigned-byte 8)` was `0`
on the interpreter and `NIL` on all three compile backends -- a computed byte buffer was a
boxed vector of `nil`, and `(incf (aref buf i))` worked on one backend and signalled on
the others.

**The dispatch is a PRELUDE HELPER, not an inline expansion, and that was the
measurement.** The upgrade space is closed at seven codes (`ArrayElementTypes`), so seven
literal `make-array` arms cover it exactly. Spelling them AT the call site is the obvious
lowering and it does not pay: wasm emits `make-array` entirely inline, so each arm is
400-1100 bytes of module and a site costs ~1.3 KB more than the two-arm form it replaces.
Measured on `--optimize=size`, raw wasm, 2026-08-31:

| program | sites | base | 7 arms inline | helper |
|---|---|---|---|---|
| `array-operations` (`aops:zeros*`) | 3 | 88,688 | 117,646 (+32.6%) | 89,130 (+0.50%) |
| `alexandria` io | 2 | 49,798 | 51,777 (+4.0%) | 52,110 (+4.6%) |
| `hello-tiny-routes` (full tiny-routes) | 1 | 874,513 | 875,067 | 875,178 (+0.08%) |
| `httpbin-tiny-routes` (full) | 2 | 906,395 | 910,685 (+0.47%) | 908,348 (+0.22%) |
| 20 synthetic sites | 20 | 34,649 | 75,442 (+118%) | 18,152 (**-48%**) |
| `zlib`, `hello-clack`-class programs with no such site | 0 | -- | +0 | +0 |

So the arms live in `LispPreludeLibrary` and every call site is one call. A program with
ONE site pays the helper's fixed ~2.9 KB and saves nothing; a program with many pays it
once and comes out ahead of even the pre-fix build. A program with no runtime designator
compiles to the same bytes as before, because the whole thing is keyed on the call shape.

**There are TWO helpers, and the split is `:fill-pointer` / `:adjustable`.**
`%make-array-et (dims et init given)` is the common shape; `%make-array-et-fp` takes `fp`
and `adj` as well. They are not one helper with two more parameters because those two
keywords are exactly what makes every arm degrade to the general representation --
spelling them in the common helper would cost it the packed arrays it exists to pick. Each
is spliced on its own surface fact, so a program pays for the shape it writes. The
fill-pointer shape is rare (two sites in array-operations' `similar-array`, one in the
whole quicklisp cache besides) but expensive inline: those two sites alone were 15 KB of
the 32.6% row above.

**How the selection works.** The call is produced by
`LispMacroExpander.lowerRuntimeElementTypeMakeArray` inside the expression compilers, long
after the prelude pass has run, so `LispPreludeLibrary.referencedBySurfaceForm` keys on the
SURFACE fact -- a `make-array` whose `:element-type` is a runtime designator and whose other
keywords the helper's signature can carry -- and `LibraryDefunPruner` roots the same entry
by the same predicate. This is `%make-array-et`'s exact shape as `%make-broadcast-stream`
and `%stream-target`, and it fails the same way: a call site injected after the pass ran
would find no defun, so the lowering asks `ctx.functions` and falls back to the inline
seven arms when the defun is absent. A site the helpers cannot serve (`:initial-contents`,
`:displaced-to`) expands inline too.

**What each arm's literal spelling buys.** It is read by the backends' own recognizers, so
the arm packs where a packed representation exists and degrades-and-remembers where it does
not -- the rank-1 rule of the section above included, since the rank is still a run-time
fact inside the helper. The `t` arm is the plain general array, and the unsupplied element
is the arm's OWN zero, which is why `given` is a parameter rather than a nil test at the
call site.

**A `deftype` ALIAS held in a VARIABLE resolves before the dispatch, through a SECOND
generated defun (2026-08-31).** The arms compare against the seven built-in spellings only,
so `et` bound to `'octet` reached the `t` arm while the interpreter -- which runs
`resolveElementTypeAlias` against the live registry -- packed. The compile paths now inject
`%make-array-et-alias`, one arm per registered alias answering the canonical spelling of
the type it names, and `lowerRuntimeElementTypeMakeArray` wraps the designator in a call to
it. A literal `:element-type 'octet` was never affected: every compile-time recognizer
resolves the alias itself.

**The table carries ONLY the aliases that name one of the six specialized codes, and that
narrowing is the whole cost story.** A general resolver -- every registered `deftype`,
which is what the item proposed -- cost array-operations **9.5 KB (+10.1%)** of raw wasm
(94,336 -> 103,895, `--optimize=size`), because alexandria registers **43** aliases (its
whole `positive-fixnum` / `non-negative-double-float` zoo) at ~220 bytes of arm each, and
every one of them upgrades to `t`, which is exactly where an unresolved designator already
lands. Narrowed, the same program is **byte-identical** to the pre-fix build, and a program
that really holds an element-type alias in a variable pays ~55 bytes per alias (a 2-alias
program: 62,853 -> 62,963). Real sources register these one or two at a time (`octet`,
`simple-octet-vector`); nothing in the quicklisp cache writes the value-carrying spelling
at all.

**`typep` had the same hole, and it was closed a day later on a DIFFERENT narrowing
(todo-618, 2026-09-02).** `(let ((ty 'octet)) (typep 3 ty))` answered `NIL` on all four
backends where SBCL 2.2.9 answers `T`: `expandRuntimeTypep` and `runtimeTypepDefun`
dispatch over the registry's LAYOUTS plus the built-in names, and an alias is neither.
`coerce` with a computed result type falls through to a computed `typep`, so it was the
same hole once more. The narrowing above genuinely does not carry over -- any of the 43
can name a type `typep` decides differently from `nil` -- and the measurement confirmed
the bill it predicted: the full table costs array-operations **+10.7%**, whether it is
spelled as one `cond` arm per alias or as the quoted data table `.todo/618` was filed to
try, because the cost is the alias NAMES becoming runtime symbols and both shapes emit
all 129 of them. What bought it instead is a narrowing on another axis: only the aliases
the PROGRAM SPELLS can ever be handed to a runtime `typep`, which takes the same program
to 2 entries and **+1.9%** and leaves `zlib` byte-identical. The full story, the numbers
and the one lite deviation are `.kb/declarations-type-checks.md`, "A `deftype` ALIAS
resolves at RUN TIME too".

**The per-site cost that pushed the arms into a helper is wasm's, and it is not specific to
this dispatch:** `WasmArrayCompiler.compileMake` emits the allocation inline at every call
site, where the JVM's is an `invokestatic` on a body emitted once. The section below has
the measured numbers and what came of them.

Pinned by `LispEvaluatorTest.evalRuntimeElementTypePicksTheSameArrayAsALiteralOne`,
`JvmLispCompilerTest.compileRuntimeElementTypePicksTheSameArrayAsALiteralOne`,
`WasmLispCompilerIntegrationTest.compileRuntimeElementTypePicksTheSameArrayAsALiteralOne`
and the `runtime-element-type-make-array` ci-spec case -- one program, one expected text,
all four backends, every answer SBCL 2.2.9's. The alias half is pinned the same way by the
`*RuntimeElementTypeResolvesADeftypeAlias` trio and the
`runtime-element-type-deftype-alias` ci-spec case.

## What a wasm `make-array` site actually costs, and what moved out of it (2026-09-02)

**A `make-array` site was a quarter of the kilobyte it was thought to be, and three
quarters of what it WAS is the DIMENSION parse -- which is now three shared callees, not
inline code.** `_arr_dims` (the argument as a buckets array of i31 sizes), `_arr_total`
(the product of that array) and `_arr_fp` (the `:fill-pointer` argument resolved against
the shape) live in `WasmArrayRuntimeBuilder` at fixed indices after `FUNC_TO_MUT_STR`,
reusing existing callable signatures so no type index moves. Every allocating shape shares
them -- general, general with `:fill-pointer`/`:adjustable`, packed float, packed integer
and the `:displaced-to` view's bound check, whose own dims-product loop is `_arr_total`
too.

**The i31 shorthand stays INLINE, on purpose.** `emitParseDims` still spells `(make-array
n)` -- a `ref.test i31`, an `array.new` of one element, and the size is the argument
itself -- and only the list arm calls. That arm is three instructions and the shape nearly
every allocation writes, so it is where a call would be felt; the list arm was already two
loops, so a call there is noise. Measured on wasmtime: a 3M-iteration loop allocating
`(make-array 8 :initial-element 1)` runs 0.41-0.55 s before and after, indistinguishable;
a 2M-iteration loop allocating `(make-array (list 2 4) ...)` -- the arm that now calls
twice -- was 0.31-0.49 s and is 0.31-0.36 s.

**The per-site numbers, and why the earlier ones were four times too big.** Measured as
the marginal module growth from 5 to 40 sites in a synthetic program, MINUS the same
program with the allocation body stubbed out -- the subtraction is what removes the
surrounding defun/`aref` harness, which the pre-2026-09 figures (400-600 bytes general,
~1,100 with a fill pointer) were counting as part of the site. Raw wasm,
`--optimize=size`:

| shape | inline, before | after |
|---|---|---|
| general, no keywords | 247 | 78 |
| general, `:fill-pointer` + `:adjustable` | 340 | 84 |
| packed `double-float` | 222 | 53 |
| packed `(unsigned-byte 8)` | 242 | 73 |

**How much of a real program is make-array at all.** Stubbing the allocation body while
KEEPING every sub-expression (so the tree shaker's reference graph is unchanged) gives the
ceiling on what any helper scheme could remove, and stubbing one kind at a time splits it.
Before the change:

| program | wasm | all | general | fp/adj | packed float | packed int | displaced | runtime `:element-type` |
|---|---|---|---|---|---|---|---|---|
| `array-operations` | 96,652 | 7,046 | 985 | 2,241 | 429 | 1,041 | 0 | 6,582 * |
| `httpbin-clack` | 741,117 | 6,979 | 1,729 | 2,906 | 431 | 1,510 | 403 | 0 |
| `llama2` | 305,504 | 6,329 | 1,809 | 1,087 | 2,471 | 578 | 403 | 0 |
| `mlp` | 37,560 | 1,754 | 1,754 | 0 | 0 | 0 | 0 | 0 |
| `nn-vec` | 68,935 | 1,320 | 247 | 0 | 1,073 | 0 | 0 | 0 |
| deep-learning ch05 | 156,909 | 1,104 | -- | -- | -- | -- | -- | 0 |

\* That column OVERLAPS the ones left of it in `array-operations` alone: those bytes ARE
the `%make-array-et` / `%make-array-et-fp` prelude defuns of the section above, whose
fourteen arms are themselves `make-array` sites. Everywhere else the columns sum to the
`all` column exactly.

**What the three callees actually saved:**

| program | before | after | delta |
|---|---|---|---|
| `array-operations` | 96,652 | 93,656 | -2,996 (-3.10%) |
| `mlp` | 37,560 | 36,570 | -990 (-2.64%) |
| `llama2` | 305,504 | 301,334 | -4,170 (-1.37%) |
| `nn-vec` | 68,935 | 68,126 | -809 (-1.17%) |
| `httpbin-clack` | 741,117 | 736,556 | -4,561 (-0.62%) |
| deep-learning ch05 | 156,909 | 156,269 | -640 (-0.41%) |

**What was NOT done, and why.** `.todo/617` proposed a whole-allocation `$_array_make`
mirroring the JVM's `_arrayMake`. What is left inline after the three callees is the
`array.new` of the data, the five `struct.new`s of the header and the element-type marker
-- 50-80 bytes a site, and the header build is five instructions that a five-argument call
would replace with four. The remaining ceiling is under 1.5 KB even on `httpbin-clack`,
against a callee that would have to branch on `:fill-pointer`/`:adjustable`/rank at RUN
time where the site knows all three at compile time, on the allocation path. A Lisp-level
helper -- the same shape, written in the source -- was measured as the stand-in: it saves
246 bytes a general site and 347 a fill-pointer one above two sites, and costs 20-30% on a
tight allocation loop. The dimension parse was the part worth moving; the rest is not.
