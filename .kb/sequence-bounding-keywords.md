# `:start` / `:end` / `:count` / `:from-end` across the count/remove/substitute family

**Invariant: the fifteen operators of the `count` / `remove` / `substitute` family take CLHS
17.2.1's bounding keywords through ONE scan shape per surface -- one expansion
(`LispMacroExpander.SeqScanScaffold`) for the call position and every backend, one runtime
(`LispEvaluator.sequenceScanValues`) for first-class use in the interpreter, and one
keyword-forwarding wrapper (`BuiltinFunctionWrappers.sequenceScanFamily`) for first-class use
on the compile paths. A call spelling NONE of them expands to exactly the loop it always did.**

`remove-duplicates`/`delete-duplicates` take the same keywords with a DIFFERENT meaning for
the window and are the section at the end of this file, not part of the fifteen.

The operators: `count`, `count-if`, `count-if-not`; `remove`, `remove-if`, `remove-if-not`;
`delete`, `delete-if`, `delete-if-not`; `substitute`, `substitute-if`, `substitute-if-not`;
`nsubstitute`, `nsubstitute-if`, `nsubstitute-if-not`. The `count` three have no `:count`.

## `:from-end` is a reversed WALK, not a tie-break for `:count`

**Measured 2026-09-11 against the ANSI suite, overturning the premise `.todo/736` and
`count-if-not`'s prelude comment both carried ("`:from-end` changes nothing for a count and
can be accepted and ignored"):** it reverses the order the elements are VISITED in, so the
`:test` and `:key` designators are called in that order. ANSI pins it with side-effecting
designators and no `:count` in sight -- `count-list.9` (a `:key` that counts its calls, 3
forward vs 4 reversed), `substitute-list.21`/`.23`, `substitute-bit-vector.24`/`.25`. An
implementation that scans forward and merely picks the last matches answers those wrong.

So every scan here is served by REVERSING the walked list and running the same forward loop:

- the element order, the designator call order and the order a `:count` budget is spent all
  follow from the walk, and the loop body never learns which direction it runs in;
- the accumulator a reversed walk builds is already in the answer's order, so the closing
  `nreverse` becomes conditional (`(if from acc (nreverse acc))`) instead of a second loop;
- `:start`/`:end` are mapped into the walk's own coordinates: `[len-end, len-start)` reversed.

## Piecewise emission (the size rule)

`SeqScanScaffold` emits each piece only when its keyword was spelled: the element index and
its `lo`/`hi` bounds for `:start`/`:end`, the count budget for `:count`, the `reverse`/`length`
pair for `:from-end`. With none of them the expansion is byte-identical to the pre-`.todo/736`
one, so no existing call site pays. `SeqScanBounds.absent()` is the gate; `wrap` binds the
keyword VALUES once, outside the loop (they used to be re-evaluated per element where the
expansions inlined them -- `:test`/`:key` still are, deliberately, so a literal `#'name`
resolves through the compilers' function-designator normalization).

- `:start`/`:end` bound the walk BY INDEX, never by handing the scan a `(subseq ...)`: the
  excluded elements must not reach a designator, and `count` used to build the subsequence
  whole before looking at the first element.
- The guard (`in range` and `budget left`) is evaluated BEFORE the match form, so a designator
  is never called outside `:start`/`:end` or past an exhausted `:count`.
- A negative `:count` acts as zero, a nil one as no limit (CLHS 17.2.1); a nil `:start` is 0
  and a nil `:end` is the end. A nil `:key`/`:test` is the ABSENT designator, not a function
  to call -- `keyedForm`/`testSpec` read a literal nil that way (ANSI spells
  `(remove 'a x :key nil)`), and the runtime twin reads a nil VALUE the same way.

## The destructive spellings

- `nsubstitute`/`-if`/`-if-not` keep rewriting the argument's own cons cells. Under
  `:from-end` the scaffold's `cells` mode walks a list OF THOSE CELLS, built in either
  direction by the same trick -- reversing the LIST would hand it fresh cells whose `rplaca`
  nobody can see. The cell list is built only when `:from-end` is spelled at all; every other
  bounded destructive scan walks the argument itself and allocates nothing.
- `delete`/`-if`/`-if-not` with ANY bounding keyword answer a FRESH sequence (they route to
  the matching `remove` scan): CLHS lets a destructive operator do that, the caller must use
  the RESULT either way, and the `rplacd` splice cannot serve `:from-end`, whose cells would
  have to be visited backwards through a singly linked spine. Unbounded, the splice is
  unchanged. The interpreter's runtime twin splices under exactly the same condition, so the
  two paths agree on when the argument is mutated.

## The three surfaces, and what keeps them honest

- **Expansion**: `LispMacroExpander` -- `expandFilter` (remove family), `substituteScan`,
  `nsubstituteScan`, `countScan`, all over `SeqScanScaffold`. Reached by the interpreter's
  call position (`rareOperatorExpansion`, whose expansion replaces the form in `evalCons`'s
  loop) and by both backends' operator cases.
- **Interpreter runtime**: `LispEvaluator.sequenceScanValues` -- one method for all fifteen,
  parameterized by `SeqScanMode` (item / predicate / negated predicate) and `SeqScanAction`
  (count / remove / substitute) plus a destructive flag. Every registration in the family goes
  through it, so `(funcall #'remove ... :count 1)` and `(remove ... :count 1)` agree. These
  registrations live in `LispEvaluator`, not `Environment` (the designators need `apply`),
  which is why the five names moved into `ShadowedBuiltins.EXPANSION_LOWERED`.
- **Compiled first class**: `BuiltinFunctionWrappers.sequenceScanFamily`, on the
  `positionFamily` model -- the wrapper re-extracts the runtime keywords with `getf` and calls
  the CALL-POSITION form, so the expansion stays the only implementation. `:test-not` is
  normalized to a complemented `:test`.
- `count-if-not` is a prelude defun and is now just `count-if` over the complemented
  predicate, forwarding the whole keyword set (`:from-end` included -- see above).

## What it moved, and what it did not (ANSI `sequences`, interpreter, 2026-09-11)

`ansi-test/measure.sh sequences`: **2,204 / 3,287 -> 2,861 / 3,287 pass** (67.1% -> 87.0%),
errors 934 -> 265, and a name-by-name diff of the FAIL/ERROR sets shows 657 tests fixed and
**zero tests that passed before failing after**. The census of `X expects keyword arguments`
rows was an upper bound of 578 plus 67 `COUNT-IF expects 2 arguments` rows; the realized gain
landed between them, because a test can fail for a second reason once the first is gone.

Two families of newly-REACHED failures this exposed, both out of scope here:

- **`*.ORDER.1/2`** (7 operators): CL evaluates the keyword VALUE forms left to right, once
  each. The scaffold binds `:start`/`:end`/`:count`/`:from-end` once, but in a FIXED order, and
  `:test`/`:key` were still INLINED into the loop, so a computed designator ran per element.
  Fixed 2026-09-11 by `LispMacroExpander.KeywordTail`, which hoists every non-literal keyword
  value of the call -- these four included -- into one source-ordered `let` chain outside the
  scaffold, leaving `wrap`'s own fixed-order bindings to copy variables:
  `.kb/sequence-designator-evaluation.md`.
- **`NSUBSTITUTE-*-VECTOR.3/.32/.33`**: a destructive substitute over a VECTOR answers a fresh
  sequence instead of writing through (`.todo/623`'s latitude). ANSI expects the argument itself
  to change (`.todo/773`).

While closing the gap, `Environment.seqAsList` was found to have no arm for a rank-1
`LispFloatArray` -- see `.kb/seq-coerce-runtime.md`.

### `nsubstitute`/`-if`/`-if-not` over a vector or string now write through (`.todo/773`, 2026-09-12)

The vector/string arm of the three destructive substitute spellings routed through
`substitute`'s own (non-destructive) vector/string handling, so it answered a correct VALUE
but left the argument unchanged -- `.todo/623`'s "a destructive form may answer a fresh
sequence" latitude, applied where ANSI actually pins identity. Fixed by giving that shared
handling a `destructive` flag (the `sort`/`nreverse` precedent, `seqResultDispatchForm`) that
writes the freshly-built vector/string back into the argument's own storage instead of
answering it as new: `LispMacroExpander.expandSubstitute`/`expandSubstituteIf` (private
3rd/4th-arg overloads) for the call-position and compiled forms, `LispEvaluator
.sequenceScanValues` (via `Environment.seqResultDestructive`) for first-class use. A
SOURCE-LITERAL string still answers a fresh copy (cannot be written in place,
`.kb/string-write-runtime.md`) -- already the destructive dispatch's own fallback, nothing
extra needed. `delete`/`-if`/`-if-not` over a vector is unaffected: it removes elements, so
the result can never be the argument's own storage.

**Measured 2026-09-12** (`ansi-test/measure.sh sequences`, suite `ca06bd9`, interpreter):
2,937 -> 2,950 / 3,287 (89.4% -> 89.7%), errors 221 -> 215, fails 129 -> 122. A name-by-name
diff of the FAIL/ERROR sets: **13 tests fixed, zero regressed** -- the census in the note
above named only the 9 `*-VECTOR.3/.32/.33` rows; the STRING twins
(`NSUBSTITUTE-STRING.32/.33/.34`, `NSUBSTITUTE-IF-STRING.32/.33/.34`) were ERRORing for the
same reason and are fixed by the same generic (string-or-vector) dispatch change, the way
`.todo/740` found more than its own census too.

## `remove-duplicates` / `delete-duplicates`: the window bounds what is CONSIDERED

The two spellings take the same 17.2.1 set minus `:count` (`:from-end`, `:test`,
`:test-not`, `:start`, `:end`, `:key`) but they are NOT the scan above, and
`SeqScanScaffold` serves them only in part:

- `:start`/`:end` bound which elements are **compared**, not which ones reach the answer.
  An element outside the window is kept VERBATIM and never handed to a designator, so the
  guard's else arm ACCUMULATES where the fifteen's skips.
- `:from-end` picks which occurrence of a duplicate set survives (the first instead of the
  last), so it decides which SIDE of the element the duplicate is looked for on -- not the
  order the walk runs in.

One forward loop serves all of it. The duplicate is looked for with the position family's
own bounded scan -- `[i+1, end)` keeping the last, `[start, i)` keeping the first -- which
is both how CLHS defines the operator and how ANSI's own reference implementation
(`auxiliary/remove-duplicates-aux.lsp`) spells it. That is what lets a **computed**
`:from-end` be a branch over two INDEX BOUNDS inside one loop rather than over two loops:
it used to be rejected outright (`IllegalArgumentException`, "expects a literal t or nil"),
which is exactly what ANSI's `remove-duplicates.order.1/2` -- whose `:from-end` counts its
own evaluation -- failed on.

The piecewise rule holds here too, and decides between TWO renderings: a call that spells
no bound and a LITERAL direction keeps the `member`-over-the-tail (or over the accumulated
answer) loop it always expanded to, index-free and byte-identical; only a bound or a
computed direction pays for the index, the guard and the inner bounded scan. The scaffold's
`forceIndex` exists for the second case, where the BODY needs the element index though no
guard does.

### First class, through the fifteen's own two pieces

Both spellings take the same keyword set as FUNCTION VALUES, and they get there the way
the fifteen do -- never with a scan shape of their own:

- interpreter: `LispEvaluator.removeDuplicatesValues`, the runtime twin of the expansion,
  registered in `LispEvaluator` rather than `Environment` because the `:test`/`:key`
  designators are applied through the evaluator (which is why both names are in
  `ShadowedBuiltins.EXPANSION_LOWERED`);
- compile paths: `BuiltinFunctionWrappers.sequenceScanFamily` with `operands` 0, the same
  keyword-forwarding wrapper the fifteen ride, so the expansion stays the only
  implementation.

Until 2026-09-12 both were a 1-argument `eql` comparison that read no keyword at all, so
`(apply #'remove-duplicates seq '(:test #'equal))` signalled `REMOVE-DUPLICATES expects 1
argument, got 3`.

**What it moved: nothing, as `.todo/778` predicted** (`ansi-test/measure.sh sequences`,
suite `ca06bd9`, interpreter, 2026-09-12): 2,933 / 3,287 before and after, and a
name-by-name diff of the FAIL/ERROR sets shows **zero fixed and zero regressed** -- the
only row that moves is `REMOVE-IF-RANDOM`, which is bad in both runs and merely trips over
a different random parameter set first. The two tests that would exercise this,
`random-remove-duplicates` / `random-delete-duplicates`, still stop at `make-sequence`
with a computed result type long before the first-class call. The gap was worth closing
for the surface agreement, not for a number.

**What it costs** (bytes, JVM `.class` / WASM, measured 2026-09-12 on the same jar):

| program | before | after |
|---|---|---|
| `(print (remove-duplicates (list 1 2 1)))` -- call position | 13,496 / 24,133 | **13,496 / 24,133** |
| `(print (mapcar #'remove-duplicates (list (list 1 2 1) (list 3 3))))` | 13,811 / 24,306 | 24,817 / 36,095 |
| the same shape over `#'remove` (the fifteen, unchanged code) | 29,275 / 36,323 | 29,553 / 36,367 |
| `(let ((f #'reverse)) (print (funcall f (list 1 2 1))))` | 46,299 / 27,168 | 46,831 / 27,210 |

So a CALL pays nothing, naming the function costs what naming `#'remove` has always cost
(and lands under it), and a program that merely opens the function-VALUE tier without
naming either spelling pays a few hundred bytes for the two wider wrappers in the
registry. `.todo/774`'s trap -- a wrapper body injected on a REFERENCE dragging the apply
runtime in, invisible to `needsApplyRuntime` -- does NOT fire here: the dedup expansion is
`do`/`position`/`cons`, and the `:test-not` normalization spells
`LispMacroExpander.twoArgumentComplement` rather than `complement`, so no `apply` reaches
the injected body (on the JVM that gate alone would show as ~41 KB, four times the
measured delta).

**Measured 2026-09-11** (`ansi-test/measure.sh sequences cons`, suite `ca06bd9`,
interpreter): sequences 2,891 -> 2,895 / 3,287 (88.0% -> 88.1%), cons unchanged at
1,082 / 1,879. A name-by-name diff of the FAIL/ERROR sets: **4 tests fixed**
(`remove-duplicates.order.1/2`, `delete-duplicates.order.1/2`), **zero that passed before
failing after**. The census of `X expects keyword arguments` rows had no plain (non-order)
duplicates test behind it -- what is left in that file is `*.error.10` (an unbound
`*mini-universe*`), `remove-duplicates.fold.4` (constant folding) and the two `random-*`
tests, which stop at `make-sequence` with a computed result type before any of this.

## Pinning tests

- `LispMacroExpanderTest.aBoundedSequenceScanEmitsOnlyTheScaffoldingItsKeywordsAskFor` (the
  piecewise/size invariant, and that `:start`/`:end` is an index bound rather than a `subseq`).
- `LispMacroExpanderTest.aBoundedRemoveDuplicatesLooksForTheDuplicateInsideTheWindow` and
  `LispEvaluatorTest.evalRemoveDuplicatesTakesTheBoundingKeywords` (the two renderings, the
  window's verbatim else arm, the computed direction, and the first-class keyword set on
  both spellings), with
  `JvmLispCompilerTest.compileAndRunRemoveDuplicatesBoundingKeywords`,
  `WasmLispCompilerIntegrationTest.removeDuplicatesBoundingKeywords` and ci-spec
  `remove-duplicates-bounding-keywords` across the four backends.
- `LispEvaluatorTest.evalSequenceScansTakeTheBoundingKeywords` (behavior, call position AND
  first-class, including the counting `:key` that only a reversed walk answers).
- `JvmLispCompilerTest.compileAndRunSequenceBoundingKeywords`,
  `WasmLispCompilerIntegrationTest.sequenceBoundingKeywords`, ci-spec
  `sequence-bounding-keywords` (all four backends).
- The rejection text is part of the contract:
  `REMOVE expects keyword arguments :TEST/:TEST-NOT/:KEY/:START/:END/:COUNT/:FROM-END, got: X`
  (`.kb/error-handling.md`, "Argument-shape errors"), pinned in ci-spec and in the
  interpreter/JVM/WASM argument-shape tests, and shown in `doc/**/macros/handler-case.md`.
