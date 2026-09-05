# An array literal is a CONSTRUCTOR, not a constant

**Invariant: every evaluation of an array literal — `#(...)`, `#nA(...)`, `#*1011`,
`#f(...)`, `#d(...)`, `#N@(...)` — answers a FRESH, independently mutable array on all four
backends. Two evaluations of one literal are never `eq`; a write through one is invisible to
the next.** A deliberate deviation from CL, which permits coalescing. The reader's array is
the SOURCE constant and never leaves the program. `PureBuiltinFolder` rests on this
(`.kb/pure-builtin-fold.md`).

Do not revisit "shared and immutable": enforcement has no store choke point or spare bit on
either compile backend (the JVM packed header `[rank, dims..., data...]` has no free slot and
the rank word is read as `(int) a[0]` in ~160 places, most inside the embedded
`JvmSimdVectorTemplate`/`JvmGpuTemplate`/`JvmBlasTemplate`; wasm packed integer vectors are a
BARE `(array (mut i8|i16|i32))` and every emitted type is `sub final`,
`.kb/wasm-gc-final-types.md`). A string literal is likewise shared-and-writable
(`.kb/string-write-runtime.md`).

- Mechanism: `JvmQuoteCompiler`/`WasmQuoteCompiler`'s `compileQuotedArray` /
  `compilePackedLiteral` / `compileSinglePackedLiteral` / `compileLiteralIntVector` |
  `compileIntVectorLiteral` rebuild at the site; `eval/LiteralArrays.materialize` is the
  interpreter's half, called from the three self-evaluating array arms of
  `LispEvaluator.eval`. The copy is **deep through nested ARRAYS only**; every other element
  passes through by identity.
- **`quote` goes the other way**: `'#(1 2 3)` is a CONSTANT, one shared object per quote
  site on all four backends; the two rules meet exactly at the `'`. `evalQuote` hands the
  datum back as is because `(quote <value>)` is also the interpreter's live-value splice
  (`LispEvaluator.quoteValue`); both compile backends memoize (`.kb/quoted-data.md`).
- **A bare `#P"..."` / `#S(...)` in code position is a CONSTANT too** — the `LispInstance`
  eval arm also carries live instances spliced back through `(quote <value>)` and cannot
  tell the two apart, so the compile side meets it instead.

## The RANK-0 array, and why `#0A` carries no parens
`(make-array nil)` is legal on all four backends: no dimensions, total size 1, one element
`aref` / `(setf (aref ...))` reach with **no subscripts**. Needed beyond conformance:
array-operations' `as-array` default method IS `(make-array nil :initial-element object)`
(`.kb/asdf.md`).
- **Every flat-index fold must start at `flat = 0` and run from `k = 0`, never at `subs[0]`
  with `k = 1`**: `LispArray.flatIndex`, `LispFloatArray.flatIndex`,
  `JvmArrayRuntimeBuilder.emitFlatN`, the two `JvmFloatArrayRuntimeBuilder` N-bodies. On the
  compile backends the subscript count is STATIC, so `JvmArrayCompiler`/`WasmArrayCompiler`'s
  `compileAref`/`compileAset` rewrite the rank-0 shape to an explicit index 0.
- **Syntax is `#0A<datum>`, no parens.** `#0A5` holds 5; `#0A(1 2)` holds the LIST `(1 2)`.
  `LispLexer` accepts `#0A` without the `(`; `LispReader.readArray` takes one `readExpr()`.
  Printers mirror it: `LispArray.renderArrayData` returns early for rank 0; the JVM's
  `_arrayToString` appends `"0A"` in place of `"("`; wasm's `emitPrintArray` tests rank 0
  OUTSIDE the packed branch and suppresses its `rparen` — which is why wasm's string table
  holds `"A"` and `"("` separately, not one `"A("`.
- A rank-0 array prints `#0A<datum>` at EVERY representation, packed float included, and
  reads back as a general rank-0 array — the round trip loses the packing (as SBCL).
- `--no-gc` refuses: `NoGcWasmCompiler.dimExprs` answers an empty list for `nil` and
  `compileMakeArray` reports "a rank-0 make-array ... is not supported".
- Related: `vectorp` of a rank-0 or rank-2 array is `NIL`
  (`.kb/declarations-type-checks.md`).

## A SPECIALIZED element type above rank 1 is the general array
**Invariant: `:element-type` selects a specialized representation only at RANK 1, except the
packed FLOAT families, which are packed at every rank. Above rank 1 a `character` or
`(unsigned-byte 8|16|32)` request answers the PLAIN GENERAL array on all four backends —
`stringp` and `vectorp` are `NIL` — and the only traces the element type leaves are the fill
for unsupplied elements and the type the array REMEMBERS.**

Degrade rather than mark, because **the marker implies rank 1** and is enforced at the ONE
constructor, so no reader checks the rank. The rank is a RUNTIME fact at every site, so all
three compile-time recognizers stayed and the rank test moved into the allocation:
- Interpreter `Environment.makeArrayBuiltin`: the character arm already required
  `dims.length == 1`.
- JVM `JvmArrayRuntimeBuilder._charVecMake`: opens with `_ivMake`'s exact rank-1 test; rank n
  returns `_arrayMake(dims, init, null, adj)` without the length-4 header. **Trap (fixed):**
  `JvmArrayCompiler.compileMake` defaulted the fill pointer by RE-COMPILING the dimensions
  expression; the default is now the unspelled `t` designator, so the dims expression is
  **evaluated exactly once**.
- wasm `WasmArrayCompiler.compileMake`: the meta-offset marker is `array.len(dims) == 1`
  instead of a compile-time `1`, emitted only under the compile-time character branch.
- `LispMacroExpander.lowerCharacterInitialContentsMakeArray` declines a LITERAL rank >= 2
  dims list, falling to `lowerInitialContentsMakeArray`.
- **The fill for an unsupplied element is one OF THE DECLARED TYPE, not `nil`** (`#\Space`,
  `0`, `0.0`), from ONE place per backend keyed by the element-type CODE:
  `ArrayElementTypes.defaultElement`. **The character fill is `#\Space` everywhere a slot is
  opened, and that is a DECISION** (`ArrayElementTypes.DEFAULT_CHARACTER`; SBCL answers
  `#\Nul` for slots `vector-push-extend`/`adjust-array` open). The general (`t`) vector keeps
  `NIL` where SBCL answers `0`.

## The degraded array REMEMBERS its element type
**Invariant: a general array carries the UPGRADED element type it was asked for on all four
backends. `array-element-type` answers it, `type-of` builds `(SIMPLE-ARRAY et dims)` /
`(VECTOR et size)` from it, `typep` takes the same specifier back, and an unsupplied element
takes that type's own zero. The representation degrades; the declared type does not.**

**The type space is CLOSED**, so it is a code, not a value: seven answers — `t`, `character`,
`(unsigned-byte 8|16|32)`, `single-float`, `double-float`; everything else upgrades to `t`
and is remembered as nothing. `am.ik.rontolisp.ArrayElementTypes` is that space (`codeOf` the
one recognizer, `valueOf`, `defaultElement`), in the ROOT package so `LispArray`,
`Environment`, `LispMacroExpander` and both codegen packages share it. Each backend spends a
slot it already had, so no array grew:
- **Interpreter**: one `int` field on `LispArray`; `become` does not touch it, so the type
  survives adjustment; `adoptElementType` is its ONE writer after construction
  (`%array-adopt-element-type`, `.kb/adjustable-arrays.md`).
- **JVM**: header slot **4**, free on every non-displaced array; a DISPLACED view hops to its
  target and reads the chain end's slot 4. The length-3 header grows to 5; the length-6
  PACKED header ALREADY HAS the slot. No header-length test moved: 4 is still the character
  vector, 6 packed, 7 the string view, `header[3] != null` still displaced.
  `_arrayMakeTyped` stamps, `_arrayElementType` reads back, `_arrayWiden` carries slot 4
  over, `_ivMake`/`_charVecMake` stamp their rank-n fallbacks.
- **wasm**: the meta MARKER word `meta.cdr.cdr`. The remembered type is `code + 1`, i.e.
  2..7, leaving 1 to the string shape — so `_charvec_p` is untouched and
  `%array-disp-offset`'s "non-displaced arrays report 0" guard already covers it.
- **The general arm of `array-element-type` is GATED, per WIDTH.**
  `LispMacroExpander.makeArrayElementTypeCodes(program, registry)` answers a bit MASK; a
  program with no qualifying `make-array` compiles byte-identically. **Trap: on wasm the mask
  rides in `Ctx.typedArrayCodes` and must be copied in `WasmAsyncEmit.freshCtx`** — without
  it a top-level `(array-element-type a)` answers `t` while the same form inside a defun
  answers the remembered type.
- **`type-of` had to ask the simplicity question FIRST**, because a typed array can have a
  fill pointer: prelude arm order is fill-pointer/adjustable, then the `t`-and-rank-1
  `simple-vector` case, then `(simple-array et dims)`. That needed
  `array-has-fill-pointer-p` and `adjustable-array-p` to answer **NIL** for a packed array
  instead of refusing it.
- Size cost ~+0.2-0.4% inside the gate. **A DISPLACED view answers `t` on all four, on
  purpose**: its meta slot carries the offset, not a type. `adjust-array` COPIES the
  remembered type (`.kb/adjustable-arrays.md`).

## A RUNTIME `:element-type` reaches the same array a literal one does
**Invariant: `(make-array n :element-type et)` with `et` a VALUE builds what the literal
spelling of that value would build on all four backends — representation, remembered element
type and zero fill.**
- **The dispatch is a PRELUDE HELPER, not an inline expansion**: wasm emits `make-array`
  entirely inline and seven arms cost ~1.3 KB per site (array-operations +32.6% inline vs
  +0.50% via the helper). A program with no runtime designator is unchanged.
- **Two helpers, split on `:fill-pointer` / `:adjustable`**: `%make-array-et (dims et init
  given)` and `%make-array-et-fp` (also `fp`, `adj`). Not one helper with two more
  parameters: those keywords are exactly what makes every arm degrade to the general
  representation. `given` is a parameter, not a nil test at the call site, so the unsupplied
  element is the arm's OWN zero.
- Selection: `LispMacroExpander.lowerRuntimeElementTypeMakeArray` runs inside the expression
  compilers, long after the prelude pass, so `LispPreludeLibrary.referencedBySurfaceForm` and
  `LibraryDefunPruner` key on the SURFACE fact. **Trap: a call site injected after the pass
  ran would find no defun**, so the lowering asks `ctx.functions` and falls back to the
  inline seven arms; `:initial-contents`/`:displaced-to` expand inline too.
- **A `deftype` ALIAS held in a VARIABLE resolves through a SECOND generated defun**
  `%make-array-et-alias` (one arm per registered alias, answering the canonical spelling),
  which the lowering wraps the designator in — otherwise `et` bound to `'octet` reaches the
  `t` arm while the interpreter packs. **That table carries ONLY the aliases naming one of
  the six specialized codes**: a general resolver cost array-operations +10.1% raw wasm
  (alexandria registers 43 aliases at ~220 bytes each).
- **`typep` had the same hole, closed on a DIFFERENT narrowing** — only the aliases the
  PROGRAM SPELLS can reach a runtime `typep` (+1.9%); the make-array narrowing does not carry
  over. Full story: `.kb/declarations-type-checks.md`.

## What a wasm `make-array` site costs
Three quarters of a site was the DIMENSION parse, now three shared callees in
`WasmArrayRuntimeBuilder` at fixed indices after `FUNC_TO_MUT_STR`, reusing existing callable
signatures so no type index moves: `_arr_dims`, `_arr_total`, `_arr_fp`. Every allocating
shape shares them. **The i31 shorthand stays INLINE, on purpose** — `emitParseDims` still
spells `(make-array n)` as `ref.test i31` + `array.new`; only the list arm calls. Per-site
raw wasm at `--optimize=size`, before -> after: general 247 -> 78; with
`:fill-pointer`+`:adjustable` 340 -> 84; packed `double-float` 222 -> 53; packed
`(unsigned-byte 8)` 242 -> 73. Program savings 0.4-3.1%.

**NOT done**: a whole-allocation `$_array_make` mirroring the JVM's `_arrayMake`. What is
left inline is 50-80 bytes a site (remaining ceiling under 1.5 KB even on `httpbin-clack`)
against a callee that would branch on `:fill-pointer`/`:adjustable`/rank at RUN time where
the site knows all three at compile time; a Lisp-level helper costs 20-30% on a tight
allocation loop. Wasm-specific: `WasmArrayCompiler.compileMake` emits inline at every site,
where the JVM's is an `invokestatic` on a body emitted once.

## Tests
ci-spec `array-literal-freshness-cross-backend`, `rank-zero-arrays-cross-backend`,
`character-element-type-above-rank-one`, `general-array-remembers-its-element-type`,
`runtime-element-type-make-array`, `runtime-element-type-deftype-alias`, `packed-float-*`,
`packed-single-float-*`, `setf-elt-cross-backend`. `LispEvaluatorTest`
(`everyArrayLiteralSyntaxIsFreshAtEveryEvaluation`,
`writingThroughAnArrayLiteralDoesNotReachTheNextEvaluation`,
`anArrayNestedInAnArrayLiteralIsFreshToo`, `rank2ArrayLiteralIsMutable`,
`makeArrayWithNoDimensionsIsARankZeroArray`,
`rankZeroArrayIsWrittenAndPrintedWithoutSubscripts`,
`rankZeroArrayLiteralReadsItsDatumWhole`, `evalMakeArrayEvaluatesItsDimensionsExactlyOnce`),
`LispReaderTest.readRank0ArrayLiteralHoldsOneDatumWithoutParens`, and the
`*CharacterElementTypeAboveRankOneIsAGeneralArray` /
`*GeneralArrayRemembersItsDeclaredElementType` /
`*RuntimeElementTypePicksTheSameArrayAsALiteralOne` / `*RuntimeElementTypeResolvesADeftypeAlias`
trios across `LispEvaluatorTest`/`JvmLispCompilerTest`/`WasmLispCompilerIntegrationTest`, plus
`LinalgSimdTest.theSelectsAndCopiesAreBitIdenticalToTheScalarOracleAtEveryShapeAndWidth`.
