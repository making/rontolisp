# An array literal is a CONSTRUCTOR, not a constant

**Invariant: every evaluation of an array literal — `#(...)`, `#nA(...)`, `#*1011`,
`#f(...)`, `#d(...)`, `#N@(...)` — answers a FRESH, independently mutable array on all
four backends. Two evaluations of one literal are never `eq`; a write through one is
invisible to the next.** A deliberate deviation from CL, which permits coalescing. A
literal behaves like `(vector 1 2 3)`: the reader's array is the SOURCE constant and never
leaves the program. `PureBuiltinFolder` rests on this (`.kb/pure-builtin-fold.md`).

Do not revisit "shared and immutable": enforcement has no store choke point or spare bit on
either compile backend (JVM packed `[rank, dims..., data...]` has no free slot and the rank
word is read as `(int) a[0]` in ~160 places, most inside the embedded `JvmSimdVectorTemplate`
/ `JvmGpuTemplate` / `JvmBlasTemplate`; wasm packed integer vectors are a BARE
`(array (mut i8|i16|i32))` and every emitted type is `sub final`,
`.kb/wasm-gc-final-types.md`). A string literal is likewise shared-and-writable here; the
pinned rule is "a literal write is legal and local to the binding you wrote through"
(`.kb/string-write-runtime.md`).

## Mechanism

Both compile backends already rebuild the literal at the site
(`JvmQuoteCompiler.compileQuotedArray` / `compilePackedLiteral` /
`compileSinglePackedLiteral` / `compileLiteralIntVector`;
`WasmQuoteCompiler.compileQuotedArray` / `compilePackedLiteral` /
`compileSinglePackedLiteral` / `compileIntVectorLiteral`).
`eval/LiteralArrays.materialize` is the interpreter's half, called from the three
self-evaluating array arms of `LispEvaluator.eval`. The copy is **deep through nested
ARRAYS only** (matching `compileQuotedVal`'s recursion); every other element passes through
by identity.

## `quote` goes the other way

`'#(1 2 3)` is a CONSTANT — one shared object per quote site on all four backends, cons as
much as array; the two rules meet exactly at the `'`. `evalQuote` hands the datum back as
is, because `(quote <value>)` is also the interpreter's live-value splice
(`LispEvaluator.quoteValue`); both compile backends memoize the datum
(`.kb/quoted-data.md`).

**A bare `#P"..."` / `#S(...)` in code position is a CONSTANT too** — one shared object per
site on all four backends. An array literal reaches the interpreter through three `eval`
arms carrying NOTHING but literals; an instance reaches it through the `LispInstance` arm,
which also carries every live instance spliced back through `(quote <value>)` and cannot
tell the two apart, so the compile side meets it instead.

## Where to look when this changes

`eval/LiteralArrays`; `LispEvaluator.eval`'s `LispArray` / `LispFloatArray` /
`LispIntVector` arms; ci-spec `array-literal-freshness-cross-backend`;
`.kb/pure-builtin-fold.md`; `doc/{en,ja}/reference/data-types.md`.

Pinned by ci-spec `array-literal-freshness-cross-backend`,
`LispEvaluatorTest.everyArrayLiteralSyntaxIsFreshAtEveryEvaluation` /
`#writingThroughAnArrayLiteralDoesNotReachTheNextEvaluation` /
`#anArrayNestedInAnArrayLiteralIsFreshToo`. Mutability itself is pinned by
`LispEvaluatorTest.rank2ArrayLiteralIsMutable`, ci-spec `packed-float-*` /
`packed-single-float-*` / `setf-elt-cross-backend`, and
`LinalgSimdTest.theSelectsAndCopiesAreBitIdenticalToTheScalarOracleAtEveryShapeAndWidth`.

## The RANK-0 array, and why `#0A` carries no parens

`(make-array nil)` is legal on all four backends: no dimensions, total size 1 (the empty
product), one element `aref` / `(setf (aref ...))` reach with **no subscripts**. It is the
empty case of the model — the flat index is the Horner fold over the subscripts, and the
fold over zero subscripts is 0.

**Every fold must start at `flat = 0` and run from `k = 0`, never at `subs[0]` with
`k = 1`**: `LispArray.flatIndex`, `LispFloatArray.flatIndex`,
`JvmArrayRuntimeBuilder.emitFlatN`, the two `JvmFloatArrayRuntimeBuilder` N-bodies. On the
compile backends the subscript count is STATIC, so `JvmArrayCompiler`/`WasmArrayCompiler`'s
`compileAref`/`compileAset` rewrite the rank-0 shape to an explicit index 0 before emitting;
the runtime folds are the definition those sites agree with, not a live path.

Needed beyond conformance: array-operations' `as-array` default method IS
`(make-array nil :initial-element object)`; without it `(aops:dims 1)` and the
0-dimensional arm of `stack-rows`/`stack-cols` fail (`.kb/asdf.md`).

**Syntax is `#0A<datum>`, no parens.** `#0A5` holds 5; `#0A(1 2)` holds the LIST `(1 2)` —
the datum is read whole, so `LispLexer` accepts `#0A` without the `(` every other rank
requires and `LispReader.readArray` takes one `readExpr()` for rank 0. Printers mirror it:
`LispArray.renderArrayData` returns early for rank 0; the JVM's `_arrayToString` appends
`"0A"` in place of `"("` and skips the closing paren; wasm's `emitPrintArray` tests rank 0
OUTSIDE the packed branch (so `#d(`/`#f(` never apply) and suppresses its `rparen` — which
is why wasm's string table holds `"A"` and `"("` separately, not one `"A("`.

A rank-0 array prints `#0A<datum>` at EVERY representation, packed float included (the
JVM's packed prefix rewrite is a `^#\d*A?\(` regex that cannot match a paren-less
rendering; the interpreter's `renderArrayData` ignores the caller's `openPrefix` for rank
0). So `(make-array nil :element-type 'double-float)` prints `#0A0.0` and reads back as a
general rank-0 array — the round trip loses the packing (SBCL spells it the same).

`--no-gc` refuses: `NoGcWasmCompiler.dimExprs` answers an empty list for `nil` and
`compileMakeArray` reports "a rank-0 make-array ... is not supported", the same shape as its
rank >= 3 refusal.

Pinned by ci-spec `rank-zero-arrays-cross-backend`,
`LispEvaluatorTest.makeArrayWithNoDimensionsIsARankZeroArray` /
`#rankZeroArrayIsWrittenAndPrintedWithoutSubscripts` /
`#rankZeroArrayLiteralReadsItsDatumWhole`,
`LispReaderTest.readRank0ArrayLiteralHoldsOneDatumWithoutParens`,
`JvmLispCompilerTest.compileAndRunRankZeroArray`,
`WasmLispCompilerIntegrationTest.compileRankZeroArray`. Related: `type-of` builds the
compound array specifier and the atomic `vector` spellings check the rank, so `vectorp` of a
rank-0 or rank-2 array is `NIL` (`.kb/declarations-type-checks.md`).

## A SPECIALIZED element type above rank 1 is the general array

**Invariant: `:element-type` selects a specialized representation only at RANK 1, except the
packed FLOAT families, which are packed at every rank. Above rank 1 a `character` or
`(unsigned-byte 8|16|32)` request answers the PLAIN GENERAL array on all four backends —
`stringp` and `vectorp` are `NIL` — and the only traces the element type leaves are the fill
for unsupplied elements and the type the array REMEMBERS.**

Degrade rather than mark, because **the marker implies rank 1** and is enforced at the ONE
constructor, so no reader checks the rank. (The character MARKER means "this general array
IS a string"; the general array carries no element-type field on any representation — the
JVM's marker IS the header length 4, wasm's is one i31 meta slot 0/1, `LispArray` has no
such field — and every consumer is a linear-indexing string operation: `_strv` /
`_charvec_to_str` / `_charvec_p` and ~30 normalizing call sites.)

The rank is a RUNTIME fact at every site, so all three compile-time recognizers stayed and
the rank test moved into the allocation:

- **Interpreter** (`Environment.makeArrayBuiltin`): the character arm already required
  `dims.length == 1`.
- **JVM** (`JvmArrayRuntimeBuilder._charVecMake`): opens with `_ivMake`'s exact rank-1 test
  (`dims` is a `Long`, or an `Object[]` cons whose cdr is not one); rank n returns
  `_arrayMake(dims, init, null, adj)` without the length-4 header. **Trap (fixed):**
  `JvmArrayCompiler.compileMake` defaulted the fill pointer by RE-COMPILING the dimensions
  expression, which `_arrayMake` rejected for rank 2 with a message about a keyword the
  program never passed. The default is now the unspelled `t` designator, so the dims
  expression is **evaluated exactly once** (it was twice here, once elsewhere).
- **wasm** (`WasmArrayCompiler.compileMake`): the meta-offset marker is
  `array.len(dims) == 1` instead of a compile-time `1`, emitted only under the compile-time
  character branch (a program with no character `make-array` is byte-identical).
- `LispMacroExpander.lowerCharacterInitialContentsMakeArray` declines a LITERAL rank >= 2
  dims list, falling to `lowerInitialContentsMakeArray`'s nested row-major fill. A dims
  expression whose rank is only known at run time keeps the rank-1 reading.

**The fill for an unsupplied element is one OF THE DECLARED TYPE, not `nil`**: `#\Space` for
a rank-n character array, `0` for a packed integer width, `0.0` for a packed float type that
fell back for a fill pointer or adjustability. All defaults live in ONE place per backend,
keyed by the element-type CODE: `ArrayElementTypes.defaultElement`.

**The character fill is `#\Space` everywhere a slot is opened, and that is a DECISION**
(`ArrayElementTypes.DEFAULT_CHARACTER`). SBCL answers `#\Space` for `make-string` and
`#\Nul` for slots `vector-push-extend`/`adjust-array` open; one rule for the whole surface
beats matching SBCL on a value the standard does not pin. The general (`t`) vector keeps
`NIL` where SBCL answers `0`, for the same reason.

Pinned by `LispEvaluatorTest.evalCharacterElementTypeAboveRankOneIsAGeneralArray` /
`#evalMakeArrayEvaluatesItsDimensionsExactlyOnce`,
`JvmLispCompilerTest.compileCharacterElementTypeAboveRankOneIsAGeneralArray` /
`#compileMakeArrayEvaluatesItsDimensionsExactlyOnce`, their
`WasmLispCompilerIntegrationTest` twins, ci-spec `character-element-type-above-rank-one`.

## The degraded array REMEMBERS its element type

**Invariant: a general array carries the UPGRADED element type it was asked for on all four
backends. `array-element-type` answers it, `type-of` builds `(SIMPLE-ARRAY et dims)` /
`(VECTOR et size)` from it, `typep` takes the same specifier back, and an unsupplied element
takes that type's own zero. The representation degrades; the declared type does not.**

**The type space is CLOSED**, so it is a code, not a value: seven answers — `t`,
`character`, `(unsigned-byte 8|16|32)`, `single-float`, `double-float`; everything else
(`fixnum`, `integer`, `bit`, a class, `(unsigned-byte 4)`) upgrades to `t` and is remembered
as nothing. `am.ik.rontolisp.ArrayElementTypes` is that space: `codeOf` (the one recognizer,
quote-unwrapping and package-qualifier-stripping), `valueOf`, `defaultElement`. It lives in
the ROOT package so `LispArray`, `Environment`, `LispMacroExpander` and both codegen
packages share it.

Each backend spends a slot it already had, so no array grew — hence three encodings:

- **Interpreter**: one `int` field on `LispArray`. `become` (adjust-array's in-place half)
  does not touch it, so the type survives adjustment; `vectorPushExtend` fills opened slots
  with `defaultElement`. `adoptElementType` is its ONE writer after construction
  (`%array-adopt-element-type`, `.kb/adjustable-arrays.md`).
- **JVM**: header slot **4**, free on every non-displaced array (slot 3, the displacement
  target, says whether slot 4 holds an offset instead). A DISPLACED view has no slot of its
  own: `_arrayElementType` hops to its target and reads the chain end's slot 4. The ordinary
  length-3 header grows to 5 (`{dims, fp, adj, null, et}`); the length-6 PACKED header
  ALREADY HAS the slot (`{dims, null, null, null, et, long[] data}`), so a remembered
  element type never costs the packing. No header-length test moved: 4 is still the
  character vector, 6 packed, 7 the string view, `header[3] != null` still displaced.
  `_arrayMakeTyped(dims, init, fp, adj, code)` stamps at allocation; `_arrayElementType`
  reads back; `_arrayWiden` carries slot 4 into the widened header; `_ivMake` and
  `_charVecMake` stamp it on their rank-n fallbacks.
- **wasm**: the meta MARKER word `meta.cdr.cdr` (previously only 0 plain / 1 mutable
  character vector; read as an offset only on a displaced array, whose chain
  `array-element-type` walks to the end). The remembered type is `code + 1`, i.e. 2..7,
  leaving 1 to the string shape — so `_charvec_p` is untouched and `%array-disp-offset`'s
  "non-displaced arrays report 0" guard already covers the new values.

**The general arm of `array-element-type` is GATED, per WIDTH.**
`LispMacroExpander.makeArrayElementTypeCodes(program, registry)` scans `make-array` calls for
element types upgrading to something other than `t` and answers a bit MASK; a program with no
such call compiles byte-identically. The scan is deliberately coarse (a rank-1 request that
never degrades counts too). **Trap: on wasm the mask rides in `Ctx.typedArrayCodes` and must
be copied in `WasmAsyncEmit.freshCtx`** — without it a top-level `(array-element-type a)`
answers `t` while the same form inside a defun answers the remembered type.

**`type-of` had to ask the simplicity question FIRST**, because a typed array can have a fill
pointer: `(make-array 4 :element-type 'double-float :fill-pointer 0)` is
`(VECTOR DOUBLE-FLOAT 4)`, not a `simple-array`. Prelude arm order is now
fill-pointer/adjustable, then the `t`-and-rank-1 `simple-vector` case, then
`(simple-array et dims)`. That needed `array-has-fill-pointer-p` and `adjustable-array-p` to
answer **NIL** for a packed array instead of refusing it (the JVM threw "not applicable to a
packed integer vector", wasm trapped on a `ref.cast`).

Size cost ~+0.2-0.4% on programs inside the gate (`hello-clack` Worker, `zlib`); a program
outside pays nothing.

**A DISPLACED view answers `t` on all four, on purpose**: its meta slot carries the offset,
not a type. `adjust-array` COPIES the remembered type rather than re-deriving it
(`.kb/adjustable-arrays.md`, "The adjusted COPY remembers the element type").

Pinned by `LispEvaluatorTest.evalGeneralArrayRemembersItsDeclaredElementType`,
`JvmLispCompilerTest.compileGeneralArrayRemembersItsDeclaredElementType`,
`WasmLispCompilerIntegrationTest.compileGeneralArrayRemembersItsDeclaredElementType`,
ci-spec `general-array-remembers-its-element-type`.

## A RUNTIME `:element-type` reaches the same array a literal one does

**Invariant: `(make-array n :element-type et)` with `et` a VALUE builds what the literal
spelling of that value would build on all four backends — the representation, the remembered
element type and the zero fill.**

**The dispatch is a PRELUDE HELPER, not an inline expansion**, because wasm emits
`make-array` entirely inline and seven arms cost ~1.3 KB per site (array-operations +32.6%
inline vs +0.50% via the helper). A program with no runtime designator compiles to the same
bytes as before.

**Two helpers, split on `:fill-pointer` / `:adjustable`.** `%make-array-et (dims et init
given)` is the common shape; `%make-array-et-fp` also takes `fp` and `adj`. Not one helper
with two more parameters: those two keywords are exactly what makes every arm degrade to the
general representation, and spelling them in the common helper would cost it the packed
arrays it exists to pick. `given` is a parameter rather than a nil test at the call site so
the unsupplied element is the arm's OWN zero.

**Selection**: the call is produced by `LispMacroExpander.lowerRuntimeElementTypeMakeArray`
inside the expression compilers, long after the prelude pass, so
`LispPreludeLibrary.referencedBySurfaceForm` keys on the SURFACE fact and
`LibraryDefunPruner` roots the same entry by the same predicate (the
`%make-broadcast-stream` / `%stream-target` shape). **Trap: a call site injected after the
pass ran would find no defun**, so the lowering asks `ctx.functions` and falls back to the
inline seven arms when it is absent. A site the helpers cannot serve
(`:initial-contents`, `:displaced-to`) expands inline too.

**A `deftype` ALIAS held in a VARIABLE resolves before the dispatch, through a SECOND
generated defun** `%make-array-et-alias` (one arm per registered alias, answering the
canonical spelling), which `lowerRuntimeElementTypeMakeArray` wraps the designator in. The
arms compare against the seven built-in spellings only, so `et` bound to `'octet` would
otherwise reach the `t` arm while the interpreter (running `resolveElementTypeAlias` against
the live registry) packs. A literal `:element-type 'octet` was never affected.

**That table carries ONLY the aliases naming one of the six specialized codes.** A general
resolver over every registered `deftype` cost array-operations +10.1% raw wasm (alexandria
registers 43 aliases at ~220 bytes of arm each, all upgrading to `t`). Narrowed, that program
is byte-identical to the pre-fix build; a real program pays ~55 bytes per alias.

**`typep` had the same hole, closed on a DIFFERENT narrowing.**
`(let ((ty 'octet)) (typep 3 ty))` answered `NIL` on all four where SBCL answers `T`:
`expandRuntimeTypep` and `runtimeTypepDefun` dispatch over the registry's LAYOUTS plus the
built-in names, and an alias is neither; `coerce` with a computed result type falls through
to a computed `typep`. The narrowing above does not carry over (any of the 43 can name a type
`typep` decides differently from `nil`), and the full table costs +10.7% however spelled,
because the cost is the alias NAMES becoming runtime symbols. What bought it: only the
aliases the PROGRAM SPELLS can reach a runtime `typep` — +1.9%, `zlib` byte-identical. Full
story: `.kb/declarations-type-checks.md`, "A `deftype` ALIAS resolves at RUN TIME too".

Pinned by `LispEvaluatorTest.evalRuntimeElementTypePicksTheSameArrayAsALiteralOne`,
`JvmLispCompilerTest.compileRuntimeElementTypePicksTheSameArrayAsALiteralOne`,
`WasmLispCompilerIntegrationTest.compileRuntimeElementTypePicksTheSameArrayAsALiteralOne`,
ci-spec `runtime-element-type-make-array`; the alias half by the
`*RuntimeElementTypeResolvesADeftypeAlias` trio and ci-spec
`runtime-element-type-deftype-alias`.

## What a wasm `make-array` site costs

Three quarters of a site was the DIMENSION parse, now three shared callees in
`WasmArrayRuntimeBuilder` at fixed indices after `FUNC_TO_MUT_STR`, reusing existing callable
signatures so no type index moves: `_arr_dims` (the argument as a buckets array of i31
sizes), `_arr_total` (the product of that array), `_arr_fp` (the `:fill-pointer` argument
resolved against the shape). Every allocating shape shares them — general, general with
`:fill-pointer`/`:adjustable`, packed float, packed integer, the `:displaced-to` view's bound
check.

**The i31 shorthand stays INLINE, on purpose.** `emitParseDims` still spells `(make-array n)`
as a `ref.test i31` + `array.new` of one element; only the list arm calls.

Per-site raw wasm at `--optimize=size`, inline before -> after: general 247 -> 78; general
with `:fill-pointer` + `:adjustable` 340 -> 84; packed `double-float` 222 -> 53; packed
`(unsigned-byte 8)` 242 -> 73. Program savings 0.4-3.1%.

**NOT done**: a whole-allocation `$_array_make` mirroring the JVM's `_arrayMake`. What is
left inline is the `array.new` of the data, five `struct.new`s of the header and the
element-type marker — 50-80 bytes a site, remaining ceiling under 1.5 KB even on
`httpbin-clack`, against a callee that would branch on `:fill-pointer`/`:adjustable`/rank at
RUN time where the site knows all three at compile time. A Lisp-level helper costs 20-30% on
a tight allocation loop. The per-site cost is wasm-specific: `WasmArrayCompiler.compileMake`
emits the allocation inline at every call site, where the JVM's is an `invokestatic` on a
body emitted once.
