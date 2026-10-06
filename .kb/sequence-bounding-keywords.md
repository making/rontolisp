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
- A negative `:count` acts as zero, a nil one as no limit, anything but an integer or nil is
  refused (CLHS 17.2.1, "A non-integer `:count`" below); a nil `:end` is the end. A nil
  `:key`/`:test` is the ABSENT designator, not a function to call -- `keyedForm`/`testSpec` read a literal nil that way (ANSI spells `(remove 'a x :key nil)`), and
  the runtime twin reads a nil VALUE the same way.
- **A nil `:start` is no bound**, only a nil `:end` (the length): every first-class surface
  passes a given nil on -- the wrappers read `:start` with `getfKwDefault` (`(getf kw :start 0)`,
  the default for an ABSENT indicator only), the runtime twins with `optionalKeywordArg`,
  `count-if-not`'s prelude unchanged -- and the bounds check below refuses it (datum `NIL`).
  Pinned by `SequenceBoundsFixture.NIL_START_PROGRAM` and ci-spec
  `sequence-operators-refuse-a-nil-start`; the same rule for `write-line` / `write-string` on a
  Gray instance is `.kb/gray-streams.md`, "Bounds on `write-line` / `write-string`". The wrappers' `getfKwDefault` is shorter than the
  older `getfKwOr`: a program carrying the compiled `eval`'s wrapper table -5.8 KB JVM /
  -2.9 KB wasm (2026-10-05).

## Every bound is checked once, before the walk

**Invariant: a spelled `:start`/`:end` is checked ONCE per call, before a designator runs or an
element is written: a negative, non-integer (a nil start included) or past-int-range bound, one
past the sequence's length and a start past its end are `subseq`'s bounds `type-error` -- datum
the refused bound, expected type its range, the report `SUBSEQ: invalid bounds S, E for KIND of
length N` (`.kb/subseq-runtime.md`, "Bounds check") -- on every backend. A call spelling no
bound carries no check.** SBCL signals the same class at the same point (its datum for a range
is the pair of bounds, the divergence `subseq` already has). It covers the fifteen,
`remove-duplicates`/`delete-duplicates`, `fill`, `replace`, the six `position`/`find`
spellings, `search`/`mismatch` and `parse-integer` (`reduce` and `read-from-string`'s window are a `subseq`, `.kb/subseq-runtime.md`). Until 2026-10-05 only a
nil start was refused (by `lo`'s `(max start 0)`, gone): a negative start acted as 0, a float
compared, a list `:end` past the length stopped there, `fill`/`replace` wrote nothing, and
`position`/`find`, believed to check already, refused only a non-integer start (measured
2026-10-05 on all four backends).

- ONE primitive, `(%check-bounds seq start end)` (`LispNames.CHECK_BOUNDS_INTERNAL`), nil or
  the refusal. Interpreter: `Environment.checkBoundingIndices`, also what every runtime twin
  calls (`sequenceScanValues`, `removeDuplicatesValues`, `positionScanValues`, the native
  `fill`/`replace`). The string comparisons' `%string-compare` calls it too, on every call
  (`.kb/characters-code-points.md`, "String comparison family"), and so do the `search` /
  `mismatch` prelude defuns, when a bound is spelled (below). JVM: `_ckBounds`, `JvmSubseqCompiler.compileCheckBounds`. wasm:
  `_ck_bounds` (`FUNC_CK_BOUNDS` after `_subseq_bad`, shaken when nothing spells a bound;
  `WasmStringRuntimeBuilder.buildCheckBoundsBody`), refusing through `_subseq_bad` in EH mode
  and with a bare `unreachable` outside it, like `subseq`. A string or vector is measured by its
  own length (O(1)); a LIST is walked only as far as the larger bound and counted whole only for
  the report, so an early `position` match keeps its early exit.
- It reads the sequence the CALLER passed, never the list a scan made of it, so a vector keeps
  its O(1) length and the report its kind: the scaffold's `original` is `seqResultDispatchForm`'s
  input variable for the remove/substitute/dedup scans (`originalOf`), `count`'s own
  `__count_in`; position/find read `lst`, which they never convert. Each lowering's own
  SEQUENCE check runs first, so a non-sequence stays the operator's `type-error`.
- Where: `SeqScanScaffold.wrap`, after the keyword values bind and before the reversed walk;
  `buildPositionScan` after `lenv`, when `:start`/`:end` is spelled -- the dedup scan's inner
  duplicate search, whose window lies inside the checked one, is expanded by `positionCallForm`
  without it, so nothing checks per element; `expandFill`/`expandReplace` at a SITE that spells a
  bound, over its arguments bound in the site's own order (`boundSiteArgument`), each spelled
  pair after its sequence's `%check-sequence` -- never inside the shared helpers, so an unbounded
  site keeps its bytes. The first-class wrappers forward `(getf kw :start 0)` and always check.
- `lo` is the start as given now. Measured 2026-10-05 (P1, pinned, min of 15): `(count 2 l
  :start s)` over a 1,000-element list 232 ms with `lo = s` vs 241 ms with `(max s 0)` -- the
  earlier "+7% without `max`" does not survive once only an index can reach the guard.
- `position`/`find` deviate from SBCL on a LIST only: its list walk is lazy (`(position 2 (list
  1 2 3) :end 9)` answers 1 and `:start 9` nil, a vector refuses both) and its compiled
  transform of a CONSTANT bound checks nothing (`(position 2 (vector 1 2 3) :start 9)` -> nil).
  Here every representation is refused alike (`SequenceBoundsFixture.BOUND_REPORT_PROGRAM`).
- `search`/`mismatch` are prelude defuns shared by every call, so the check is in the body,
  after both `%check-sequence` lines and before the cursors are seeded: `(when (or s1p end1)
  (%check-bounds seq1 start1 end1))`, `s1p` the start's supplied-p. A range with no start
  given and a nil end is the whole sequence, inside it by construction. A list too short for
  its bound is refused like every other representation; SBCL's `search` over a LITERAL
  needle walks a list `sequence-2` lazily (`(search '(2) (list 1 2 3) :start2 9)` -> NIL),
  over `(list 2)` it refuses. The interpreter's `SequenceScanFast` arm declines every bad
  range and the defun refuses it. Before (measured 2026-10-06, four backends): only a
  negative or nil start reached an error, as `elt`'s or the arithmetic's; a past-the-end
  `:start1` answered 0 or 9, a crossed `:start2`/`:end2` NIL, `:end2 9` over a vector 1, and
  `(mismatch s s :start1 -1)` over two strings trapped on both wasm legs (out-of-bounds array
  access). Clojure's `index-of` / `.indexOf` lowerings passed a Java `fromIndex` straight
  through; they clamp it now (`.kb/clojure-frontend.md`).
- Guarded vs always checked (2026-10-06, the two candidates): speed, pinned, min of 15
  steady-state reps, one loop shape per program (JVM 2M calls; P1 200K), base / always /
  guarded -- keyword-free `search` (3 chars in 10) JVM 79-82 / 78-85 / 79-82 ms, P1
  453-454 / 463 / 434-448; bounded `search :start2 1 :end2 9` JVM 137-176 / 155-176 /
  146-166, P1 532 / 544-547 / 536-537; keyword-free `mismatch` (8 chars) JVM 57-62 / 71-76
  / 59-61, P1 434-435 / 435-440 / 419-431. Under C2
  (`-XX:-UseJVMCICompiler`) always-checked was flat alone but +20-45% in a program mixing
  the three shapes. A guard on `(eql start1 0)` instead of a supplied-p measured slower than
  either (JVM bounded 196-246, `mismatch` 75-79). Graal runs of the mixed-shape program are
  bimodal on base too (fast ~80 ms / slow ~270 ms for the first loop): base 7/12 fast,
  always-checked 2/12, guarded 3/8 vs base 5/8 in a second batch -- a lottery, not a
  ranking. Bytes (JVM / P1 / component, 793 programs: every ci-spec case, the
  `examples.yaml` examples, size-report, bench-report): 2,271 of 2,327 artifacts
  byte-identical either way, the 56 that differ all calling `search`/`mismatch`. Always
  checked: JVM sum -122 B (-151..+803), P1 -1,051 (-190..+544), component -1,031 (the
  dropped `integerp`/`>=` cursor guards pay for the check on wasm). Guarded: JVM +4,751
  (0..+1,046), P1 -109 (-128..+554), component -89. A one-site program
  `(print (search "b" (copy-seq "abc")))`: base 31,462 / 7,966 / 9,146, always 31,702 /
  7,967 / 9,147, guarded 31,945 / 7,977 / 9,157; with a `handler-case` 39,249 / 17,716 /
  18,950 -> 39,732 / 17,797 / 19,034 guarded. The guard won: ~240 B JVM per program buys
  the keyword-free JVM `mismatch` back (+20% checked always).
- `parse-integer`: `expandParseInteger` (call position on every backend, and the compile
  paths' first-class wrapper, which forwards both bounds) emits `(%check-bounds __pi_s
  __pi_start __pi_endraw)` when `:start` or `:end` is spelled, after every argument has run
  (the `:radix` and `:junk-allowed` forms included) and before the scan; the interpreter's
  `#'parse-integer` calls `checkBoundingIndices` under the same condition. A `:radix` outside
  2..36 is refused BEFORE the bounds (`.kb/error-handling.md`, "The radix"). A fill-pointer
  string is measured by its fill pointer. Before (measured 2026-10-06, four backends): `:start
  9` and a crossed range were the scan's `simple-error` (no integer); `:end 9` the `char`
  `type-error` on the interpreter and JVM and an out-of-bounds trap on both wasm legs over a
  fresh string; `:start -1` the interpreter's `char` `type-error`, the JVM's, a trap on wasm
  over a fresh string and `1123` over the LITERAL `"123"` (read before the string); a
  fill-pointer string's `:end 4` read past the fill pointer (`1234`); the first-class
  interpreter `:end nil` a `type-error`. Its first-class Java walk also indexed UTF-16 units
  and skipped `Character.isWhitespace`; it walks code points over the expansion's five
  whitespace characters now. Cost (JVM / P1 / component bytes, 795 programs: every ci-spec
  case, the `examples.yaml` examples, size-report, bench-report): 2,253 of 2,333 artifacts
  byte-identical; the 80 that differ call `parse-integer` with a bound themselves, through a
  library (`tokenizers.lisp`, `objc.lisp`) or through the first-class wrapper (+24 JVM / +10
  wasm on a program carrying the compiled `eval`'s wrapper table). JVM 30, sum +6,200 B (max
  +2,238, the new ci-spec case); P1 25, +1,855 (max +456); component 25, +1,865. One site
  `(print (parse-integer (copy-seq "x12") :start 1))`: 28,619 / 8,356 / 9,505 -> 29,196 /
  8,444 / 9,593; with a `handler-case` +582 / +190 / +193. Speed (pinned, min/median of 15,
  `(parse-integer s :start st)` over 12 characters): JVM 4 M calls 185-199/208-216 ->
  189-191/214-218 ms, P1 400 K 512-520/522-531 -> 510-511/519-520, component 516-518/527-530
  -> 511-516/523-528, interpreter 100 K 2,381-2,400 -> 2,423-2,446. ANSI `numbers`
  (interpreter, suite `ca06bd9`): 1,273 -> 1,274 / 1,444 (`PARSE-INTEGER.ORDER.1`, the
  evaluation order above), zero regressed; `strings` unchanged. Pinned by
  `ParseIntegerBoundsFixture` (`.PROGRAM`, sbcl's answers, ci-spec
  `parse-integer-refuses-a-bad-bound`; `.REPORT_PROGRAM`; `.ORDER_PROGRAM`) in the three
  backend suites, the shape by
  `LispMacroExpanderTest.aParseIntegerCallChecksASpelledBoundOnceAfterItsArguments`.
- `read-from-string` given more than the string is the prelude `%read-from-string-full`
  (`.kb/read-load-streams.md`), whose window is `with-input-from-string`'s `subseq` of the
  string: that IS the check, raised once after every argument has run and before a character is
  read, so the defun spells no `%check-bounds` (it would check twice). The refusal and its report
  are `%check-bounds`' to the byte on all four backends (`ReadFromStringLambdaListFixture`
  `.REPORT_PROGRAM`). Before, every bound was ignored: the read started at 0.
- `count`/`count-if` bind their operand outside the scaffold when any bounding keyword is
  spelled: the scaffold binds the sequence outside the loop, so `(count (f) (g) :start 1)` ran
  `(g)` before `(f)` on the compile paths and the interpreter's call position (SBCL: item first).
- JVM: `_ckBounds` answers non-null and the SITE calls the report (`_ckBoundsBad`, which counts
  a list and picks the kind). With the report inside, Graal OSR-compiled a hot first call before
  `_ckBounds` had a profile, inlined it whole with `_subseqBad`'s string building, ran out of
  budget and left the loop's own helpers (`_car`, `_cdr`, `_pEql`, `_fx$3`) as calls: a first
  `(count 2 l :start s)` loop ran ~3x slower (2.8-3.2 s vs 0.91-0.95 s); a branch the caller's
  own profile shows untaken keeps the report out, 607-654 ms vs base 584-648 (pinned). The site
  re-reads its arguments (the lowerings hand variables and literals) rather than spilling them:
  spilled, each site added a full stack-map frame, +4.7 KB on a class carrying the wrapper
  table; re-read, +1.2 KB.
- Cost (2026-10-05, JVM / P1 / component bytes, 782 programs: every ci-spec case, the non-GUI
  examples, size-report, bench-report): 1,931 of 2,282 artifacts byte-identical (`hello`,
  `pi_approx`, `dom_reactor`, every bench-report program); 351 differ, all spelling a bound
  themselves or through a library or a first-class wrapper body -- 19 JVM classes in
  constant-pool ORDER only (a wrapper's check emitted, then shaken), same size. JVM: 138, sum
  +110 KB, max +3.2 KB (a 3.8 MB ningle class), +1.2 KB typical of the wrapper table. P1: 103,
  sum -38 KB, -4.0 KB..+1.1 KB; component 110, sum -36 KB (the dedup scan's inner search lost
  its dead vector arm; `max` left). zlib +1.2 KB / +1.1 KB / +1.1 KB. A one-site program
  (`(count 2 l :start s)`): +961 / +132 / +132; with a `handler-case` +1,105 / +455 / +460. No
  condition machinery is pulled in (`read-sequence`'s Lisp check cost +19-28 KB,
  `.kb/read-load-streams.md`).
- Speed (2026-10-05, pinned, min / median of 15 steady-state reps): within noise on both
  backends -- list `count :start` JVM 88/90 -> 89/91 ms, P1 228/246 -> 220/242; vector `count
  :start :end` 185/206 -> 185/194, 1,139/1,195 -> 1,092/1,227; string `position :start` 54/56 ->
  54/56, 2,208/2,353 -> 2,004/2,249; list `remove :start` 50/54 -> 51/53, 99/108 -> 100/114.
- ANSI (interpreter, suite `ca06bd9`, 2026-10-05): sequences 3,062 -> 3,065 / 3,287
  (`ARRAY-FILL-9`, `ARRAY-FIXNUM-FILL-9`, `ARRAY-UNSIGNED-BYTE8-FILL-9`: `:end -1`), zero
  regressed; strings, cons, arrays, iteration unchanged name by name.
- Pinned by `SequenceBoundsFixture.BAD_BOUND_PROGRAM` (sbcl's answers; ci-spec
  `sequence-operators-refuse-a-bad-bound`) and `.BOUND_REPORT_PROGRAM` (the slots and text), in
  `LispEvaluatorTest`, `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest` (P1 and
  component); the shapes by `LispMacroExpanderTest.aBoundedSequenceScanEmitsOnlyTheScaffoldingItsKeywordsAskFor`
  and `.aBoundedRemoveDuplicatesLooksForTheDuplicateInsideTheWindow`.

## A non-integer `:count` is refused once, before the bounds

**Invariant: a `:count` that is neither an integer nor nil is the operator's `INTEGER`
`type-error` over the value (datum as given, report `OP: The value V is not of type INTEGER`),
raised once, before the bounds are checked and before a designator runs, on every backend; nil,
a negative and a bignum count answer as CLHS 17.2.1 reads them (no limit, zero, more than any
list holds).** SBCL signals the same class and datum, and checks the count BEFORE the bounds
(`(remove 2 l :start 9 :count 1.5)` -> datum 1.5) -- except on a `:from-end` walk, where its
bound check comes first, so that order is not pinned.

- Expansion: `SeqScanScaffold.wrap` emits `(if (or (null c) (integerp c)) nil
  (%operand-type-error c 'op 'integer))` over the bound value `countv`, outermost of the checks
  (`SeqScanBounds.operator` is the call's head). A literal integer or nil `:count` emits nothing,
  and a call spelling no `:count` is unchanged. The first-class wrappers feed the same
  expansion, so `apply` / `funcall` of `#'remove` etc. refuse alike.
- Interpreter twin: `LispEvaluator.requireCount` throws `OperandTypeException.of(value, INTEGER,
  name)`; a `LispBigInteger` is a budget of `Long.MAX_VALUE` (negative: zero).
- Before (measured 2026-10-06, four backends): a float or ratio `:count` was a budget (`1.5` spent
  on two matches, `1/2` on one) in call position and through the compiled wrappers, the
  interpreter's `funcall` refused it with datum NIL (the message-only `type-error`) and also
  refused a bignum; a symbol or string was already refused by `(max count 0)`, datum as given.
- The delete / nsubstitute / substitute fresh-sequence paths expand through `remove`'s /
  `substitute`'s lowering, so on the compile paths a bounded or counted `delete` reports under
  `REMOVE` (a pre-existing wobble of every type-error there, not of this check).
- Pinned by `SequenceBoundsFixture.BAD_COUNT_PROGRAM` (sbcl's answers) in the three backend
  suites and `LispMacroExpanderTest.aRemoveCallChecksAComputedCountOnceBeforeTheBounds`.

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
- The bounds check: `sequenceOperatorsRefuseABadBound` / `aBadSequenceBoundReportsAsSubseqDoes`
  in `LispEvaluatorTest`, `WasmLispCompilerIntegrationTest` (P1 and component) and,
  `compileAndRun`-prefixed, `JvmLispCompilerTest`, over `SequenceBoundsFixture`; ci-spec
  `sequence-operators-refuse-a-bad-bound` (sbcl's answers). `search`/`mismatch`:
  `searchAndMismatchRefuseABadBound` / `aBadSearchOrMismatchBoundReportsAsSubseqDoes` in the
  same three classes over `SearchMismatchBoundsFixture`, ci-spec
  `search-and-mismatch-refuse-a-bad-bound`.
- The rejection text is part of the contract:
  `REMOVE expects keyword arguments :TEST/:TEST-NOT/:KEY/:START/:END/:COUNT/:FROM-END, got: X`
  (`.kb/error-handling.md`, "Argument-shape errors"), pinned in ci-spec and in the
  interpreter/JVM/WASM argument-shape tests, and shown in `doc/**/macros/handler-case.md`.
