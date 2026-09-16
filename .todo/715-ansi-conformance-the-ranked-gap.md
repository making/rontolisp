# ANSI conformance: what the suite says to fix next

Difficulty: Medium

This item is the READING of `ansi-test/results/interpreter.md`: which gap is
worth closing, in what order, and which todo owns it. The numbers themselves
live in that report and are not duplicated here -- only the ordering, the owner
and the reason.

**Re-measure before you read.** `ansi-test/measure.sh` with no argument rewrites
the checked-in baseline; pass every chapter name instead and it writes
`results/partial.md`, which is what a local re-read wants (a full run is ~8
minutes on 64 cores). Then rank from `ansi-test/results/logs/*.log`, not from
the report's reason table -- see "How to count" below.

## Baseline (re-measured 2026-09-12, suite revision `ca06bd9`; see history)

**13,356 / 19,482 tests pass (68.6%)** -- 2,471 wrong values, 3,655 signalled,
463 top-level forms lost. Later local runs: 13,771 (09-13, after `.todo/797`),
13,808 (09-13, after `.todo/807`'s first pass); the checked-in report has since
been rewritten twice by the daily workflow and reads **13,810 / 19,485
(70.9%)**. A 09-14 `iteration`-only run projects **13,923 / 19,485 (71.5%)**
after the `.todo/029` slices (projection, not a re-measure).

Against the previous reading in this file (55.5%, 10,809 / 19,461): `.todo/736`,
`.todo/740`+`.todo/776`, `.todo/744`, `.todo/775`, `.todo/778`, `.todo/772`,
`.todo/773`, `.todo/679`, `.todo/741`, `.todo/742`, `.todo/743` and the
`subtypep` valid-p below all landed. **Every per-row figure in the old table is
dead; do not carry one forward.**

## How to count

The report's "Most frequent failure reasons" table merges two units: `ERROR
<test>` lines, one per TEST, and `%%%EVAL`/`%%%READ` lines, one per lost FORM
**per chapter** -- and the aux files are loaded by all 25 chapters, so one
failing aux form appears 25 times beside rows that are test counts. **Every
number below is TEST-level `ERROR`/`FAIL` lines from `results/logs/`.**

Even then a count is an UPPER bound, and it is blind in both directions:

- **Blind upward**: a test that stops erroring often fails for a SECOND reason
  (`.todo/744` priced at 156, worth 41).
- **Blind downward**: it can only count what is NAMED. A present-but-wrong
  operator is invisible (`.todo/740` priced at 458, worth 514).
- **Measure the effect as a DIFF of failing test NAMES, before and after**, and
  report fixed AND regressed. An ERROR-line census has disagreed with the real
  effect every time it has been checked.

## The ranking

### 1. The `universe.lsp` cascade is now ONE link, and it is out of scope

`*MINI-UNIVERSE*` 252 tests + `*UNIVERSE*` 183 = **435 tests**, the largest
single row on the board. The chain that used to have five links has four of them
closed (`.todo/679`, `.todo/742`, and the `logical-pathname-translations` /
one-argument `(compile nil)` forms turn out not to block it). What remains is
exactly one form:

```lisp
(defparameter *methods*
  (list (find-method #'meaningless-user-generic-function-for-universe nil
                     (mapcar #'find-class '(integer integer integer)))))
```

`find-method` is **decided against** (`.kb/clos.md`, "Out of scope": classes are
compile-time-static and the dispatch tables depend on it). So 435 tests sit
behind a decision, not a gap. Revisit only if MOP reflection is ever reopened --
and note that closing it ADMITS ~435 tests that may then fail.

### 2. Operator and surface families, by tests billed

| family | tests | owner |
|---|---:|---|
| the reader syntax-type surface, re-measured 2026-09-13 a SECOND time, after `.todo/807`'s first pass (`#+` at runtime, radix rationals and `#<n>R`, reader labels) closed 35 | 108 | `.todo/807` -- whose largest remaining family is not a syntax at all but "a read error must be a `reader-error`/`end-of-file` CONDITION", 28 tests, and needs `.todo/039` first |
| bit arrays: the eleven `bit-*` ops (~310) plus `bit-vector-p` 38 / `simple-bit-vector-p` 28 / `array-in-bounds-p` 27 | ~400 | `.todo/043`, `.todo/180` |
| `loop` -- four slices landed 2026-09-14 for 113 (uninterned `#:kw` 45, NIL no-binding 15, any-order numeric 12, named-loop block 39; `iteration` 63.6% -> 77.0%, 0 regressed); left: missing `program-error`/`type-error` validation ~30, hash/`across` destructuring ~9, dotted `append` ~5, typed init ~6 | ~50 | `.todo/029` |
| the runtime package API: `unuse-package` 47, `delete-package`, `import`/`unexport` -- plus `set-up-packages` 56, which is the suite's own aux defun and a LOST FORM, not an operator | ~150 | `.todo/741` closed 2026-09-09 covering only part; **re-file before quoting** |
| stream constructors: `make-two-way-stream` 53, `make-concatenated-stream` 40, `make-echo-stream` 33, plus `open`'s `:if-exists`/`:direction`/`:element-type` | ~200 | `.todo/387` |
| `rational`/`rationalize`/`logcount`/`integer-decode-float` et al (was: `float-radix` 80, `rational` 33, `logeqv` 22, `lognor` 21 -- Slice A + the ash fix closed the rest; 2026-09-15 Slice B closed `rational` for 15 numbers + 7 misc, 0 regressed; Slice C closed `logcount`/`rationalize`/`integer-decode-float` for 16 numbers + 15 misc, 0 regressed; the ratio->float conversion closed `RATIONAL.1/.3`, `RATIONALIZE.1/.3`, `/.12` and `*.12` for 6 numbers, 0 regressed; 2026-09-15 exact float-vs-exact comparison closed the eight `*.17`/`*.18` + eight `BIGNUM.FLOAT.COMPARE.1A-4B` for 16 numbers, 0 regressed; 2026-09-15 the --no-gc exact i64-vs-f64 path landed (not ANSI-measured: numbers 220 -> 220, misc 38 -> 38, 0 regressed -- nothing left here) and 2026-09-15 the WASM-GC exact float-vs-exact path landed (also not ANSI-measured, same 220/38 no-op -- exactness everywhere now; near ties stay out of ci-spec since --no-gc cannot spell a ratio) and 2026-09-15 the `float-sign`/`float-digits` smalls landed as prelude defuns (also not ANSI-measured, same 220/38 no-op -- no dedicated suite tests; `ATAN.IEEE.2` exercises `float-sign` but is structurally unpassable under `rt-shim.lisp`, whose `deftest` has no `:description` support) and 2026-09-16 the `ldb-test` prelude landed (`.todo/818`: **numbers 220 -> 219** via `LOGBITP.7`, **misc 38 -> 26** via eleven `LDB-TEST is undefined` tests + the `MISC.131` knock-on, 0 regressed) | 219 + 26 | `.todo/037` (done: `.todo/818` closed the byte family) |
| a `setf` place the expander does not support | 79 | `.todo/001`, `.todo/041` |
| `pprint-tabular`/`-fill`/`-linear` (27+) and `setf readtable-case` | ~140 | `.todo/041`, `.todo/001` |
| `read-from-string`'s lambda list (`eof-error-p`, `:start`/`:end`, `:preserve-whitespace`) -- six `READ-FROM-STRING.*` tests; the INDEX landed 2026-09-13 | 6 | `.todo/214` |
| `copy-structure` | 31 | `.todo/043` |

`read-from-string`'s index was also the SECOND HALF of every `*read-suppress*`
test (each wants `(nil <index>)`); both landed together on 2026-09-13, which is
what the entry below measures.

### 3. Billed by the suite, DECIDED AGAINST -- do not read these as gaps

- MOP reflection: `find-method` (and with it the 435 above),
  `compute-applicable-methods`, `ensure-generic-function`, `add-method`,
  `define-method-combination`. `.kb/clos.md`, "Out of scope".
- `slot-value` as a first-class function. It is in `CL_MACROS` by design
  (`.kb/clos.md`); `SLOT-VALUE is a macro or special operator, not a function`
  is the model working. 66 tests share that message with other names.
- `compile-file` / `compile-file-pathname` (30): "no file compiler -- a program
  is compiled whole".
- `class-precedence-list-foo` (67, all in `types-and-classes`): the suite builds
  it with `#.` read-eval over `(:method-combination list)`. One aux form, not an
  operator.
- `packages` at 34.2% is partly the DRIVER: it skips every `(in-package ...)`,
  so the chapter runs in `COMMON-LISP-USER` throughout (`.todo/739` section 3).
  Settle 739 before treating that rate as a capability measurement.

## The second instrument: the _Practical Common Lisp_ corpus

Peter Seibel's book code -- twelve ASDF systems of ordinary 2005 Common Lisp,
diffed byte for byte against SBCL. The standing verdict is `.kb/asdf.md`, "The
_Practical Common Lisp_ book corpus". It ranks by WHETHER A PROGRAM RUNS AT ALL,
while the suite ranks by TESTS LOST, which over-weights operator families nobody
calls. **The judgment axis has not changed: an item BOTH instruments name is the
one to take first.**

As of 2026-09-08 the corpus needs no shim and no replacement `.asd`, and names
exactly one live gap: **`.todo/041`'s missing right margin**, which three systems
(`simple-database`, `test-framework`, `pathnames`) still differ by. The suite
independently puts `.todo/041` in the table above (`printer` at 42.1%). It is
the only item both instruments name, and on the corpus's own axis it is the last
one standing.

What the corpus still says is NOT urgent, against the suite's ranking: the
runtime package API and complex numbers. The corpus uses only
`defpackage`/`in-package`, and both work.

## Worked so far

Only the entries whose FINDING outlives the change are kept; the rest are in
`.todo/history/`.

- **2026-09-16, `.todo/818` `ldb-test`** -- the last byte-family member as a
  prelude defun over `ldb` (`LispPreludeLibrary`, the `decode-float`
  precedent: going through `ldb` dodges the Slice A `deposit-field`/`dpb`
  mix-up by construction) + `LispNames` + `CL_FUNCTIONS` (`LDB-TEST` out of
  `CL_EXPORTED_ONLY`, 978 externals unchanged) + ci-spec case + EN/JA docs;
  `--no-gc` refuses it outright beside `RATIONALIZE` (the `ldb` expansion
  reads the bytespec cons back). **numbers 220 -> 219 (`LOGBITP.7`), misc 38
  -> 26 (eleven `LDB-TEST is undefined`: `MISC.23/.28/.33/.100/.111/.113/
  .114/.180/.234/.358/.382`, plus the `MISC.131` knock-on, explicitly PASS
  after), 0 regressed**, as a diff of failing test NAMES. WASM-GC pins stay
  in the exactly-representable range (small integers). Pinned by
  `LispEvaluatorTest#evalLdbTest`,
  `JvmLispCompilerTest#compileAndRunLdbTest`,
  `WasmLispCompilerIntegrationTest#ldbTest`,
  `NoGcWasmCompilerTest#rejectsLdbTest`.
- **2026-09-15, `.todo/037` smalls (`float-sign`, `float-digits`)** -- prelude
  defuns over existing primitives (`LispPreludeLibrary`, the `decode-float`
  precedent: `float-sign` reads a zero's sign through `(/ 1.0 f)`, `float-digits`
  answers `53` for every normal double and `1127 - j` for a subnormal after `j`
  exact doublings) + `LispNames` + `CL_FUNCTIONS` (two names out of
  `CL_EXPORTED_ONLY`, 978 externals unchanged) + ci-spec cases + EN/JA docs;
  `--no-gc` refuses both outright beside `RATIONALIZE`. **numbers 220 -> 220,
  misc 38 -> 38, 0 regressed**, as a diff of failing test NAMES (no dedicated
  suite tests). Two adjacent findings, both hands-off: an `&optional` lambda
  silently ignores extra arguments (a lambda-list gap, unpinned), and the
  `ATAN.IEEE.1/.2` failures are structural -- `rt-shim.lisp`'s `deftest` has no
  `:description` support, so the form binds to the keyword (`got
  (:DESCRIPTION)`, byte-identical before/after). Pinned by
  `LispEvaluatorTest#evalFloatSign`/`#evalFloatDigits`,
  `JvmLispCompilerTest#compileAndRunFloatSign`/`#compileAndRunFloatDigits`,
  `WasmLispCompilerIntegrationTest#floatSign`/`#floatDigits`,
  `NoGcWasmCompilerTest#rejectsFloatSignAndFloatDigits`.
- **2026-09-15, `.todo/037` WASM-GC exact float-vs-exact comparison** -- a float
  beside an exact integer (any tier, limb included) or ratio compares exact
  values on the GC backend (`_rat_cmp_bits` decomposes the finite float from its
  raw bits and cross-multiplies through `_int_new`/`_big_ash`/`_big_mul`/`_big_cmp`
  alone, no new runtime function; both-float pairs keep the f64 ladder, NaN
  unordered, infinities by side, non-exact operands keep the `_as_f64` traps),
  and the comparison/min-max call sites take the unboxed f64 path only when BOTH
  operands are `isDefinitelyDouble` (the JVM gate's distinction). **numbers 220
  -> 220, misc 38 -> 38, 0 regressed** (interpreter untouched; this path is not
  ANSI-measured), as a diff of failing test NAMES, plus a 4,412-case
  interpreter-vs-WASM-GC differential sweep with 0 mismatches and byte-identical
  pure-int/pure-double modules (a mixed module carries ~+1.5-1.9 KB). Two traps:
  the cross-multiplication reads (FL vs EX), so a float in b position needs the
  RESULT signum flipped (negating one side is not the comparison's negation),
  and an empty ELSE on that flip's `if` breaks `WasmRefTypeFolder` (both arms
  explicit). Pinned by
  `WasmLispCompilerIntegrationTest#floatExactComparisonNearTie`; design in
  `.kb/wasm-bignum.md`.
- **2026-09-15, `.todo/037` --no-gc exact i64-vs-f64 comparison** -- a mixed
  int/float `=`/`<`/`>`/`<=`/`>=` compares exact values on the scalar backend
  (the float's exact binary value against the i64, bit-decomposed inline per
  site: shift-up with a survival check for `exp >= 0`, truncating quotient plus
  remainder for `exp < 0`; NaN unordered, infinities beyond every i64), and
  `min`/`max` decide mixed rounds through the same helper while still answering
  the joined f64 values. Only a statically INT-ish/FLOAT pair takes the path.
  **numbers 220 -> 220, misc 38 -> 38, 0 regressed** (interpreter untouched;
  this path is not ANSI-measured), as a diff of failing test NAMES, plus a
  10,716-case no-GC-vs-interpreter differential sweep with 0 mismatches. Two
  traps: a min/max SELECT needs its flag on TOP (stage the values first -- the
  helper is stack-net-zero, so deciding on top of staged values is sound), and
  three scratch locals per site trips wasmtime's locals cap on a many-site
  function (one per-function triple shared across sites, one local per site).
  Pinned by `WasmLispCompilerIntegrationTest#noGcIntFloatComparisonIsExactPast2Pow53`;
  design in `.kb/no-gc-scalar-wasm.md`.
- **2026-09-15, `.todo/037` exact float-vs-exact comparison (interpreter + JVM)** --
  a float beside an exact number compares exact values (the float's exact binary
  value via `rationalOfDouble`/`_frat` + cross-multiplication; NaN unordered,
  infinities beyond every exact number, the non-number funnel stays `Expected
  number`), and the JVM's unboxed DCMPL now needs BOTH operands `isDefinitelyDouble`
  (the min/max gate's distinction). **numbers 237 -> 220 (16 fixed: the eight
  `*.17`/`*.18` + `BIGNUM.FLOAT.COMPARE.1A-4B`, the latter deterministic now, not draw
  luck), misc unchanged, 0 regressed**, as a diff of failing test NAMES. `SQRT.17`
  flipped by draw luck and is excluded (its operands stay under 2^53, where both
  semantics agree -- verified, not assumed). No-GC/WASM-GC kept f64 comparison
  with representable-range agreement pins only; a near tie still rounded to
  equality there (`(= 0.6666666666666666 2/3)` was T on WASM-GC, NIL on
  interpreter/JVM -- even tiny ratios sit strictly inside half an ulp, so no
  component bound saves f64) -- both exact paths have since landed with designs
  recorded (no-GC in `.kb/no-gc-scalar-wasm.md`, WASM-GC in `.kb/wasm-bignum.md`;
  `.kb/jvm-double-arithmetic.md` for the gate rule).
- **2026-09-15, `.todo/037` ratio->float conversion** -- correctly-rounded
  nearest double on the interpreter (`LispRatio.doubleValue`, exact `BigInteger`
  quotient with sticky bit) and the JVM (new generated `_ratToDouble`, no new
  runtime class); WASM-GC unchanged (its `_as_f64` f64 division already rounds
  correctly for i31 components). **numbers 240 -> 234 (6 fixed: `RATIONAL.1/.3`,
  `RATIONALIZE.1/.3`, `/.12`, `*.12`), misc unchanged, 0 regressed**, as a diff of
  failing test NAMES. The round trips are identities now (exact `rational` +
  correctly-rounded `float`), so the two random `*.3` pass deterministically.
  `*.12` was conversion-caused, not an epsilon-model gap. Unchanged: the six
  float-vs-ratio `=`/compare (`.todo/037` item 2) and the universe-cascade ERRORs
  (§1 out of scope). Cost: ~300 bytes per numeric program, so the 8,000-byte
  budget in `aProgramThatNeverNamesAnArrayOperatorCarriesNoArrayRuntime` went to
  8,400 with the reason on the assertion (a ratio gate needs a recompile net --
  disproportionate).
- **2026-09-15, `.todo/037` numbers Slice C (`logcount`, `rationalize`,
  `integer-decode-float`)** -- prelude defuns (`LispPreludeLibrary`, the
  `decode-float` precedent: one Lisp implementation on interpreter/JVM/WASM-GC,
  first-class and multi-value free) + `CL_FUNCTIONS` classification + `ci-spec`
  cases + EN/JA docs; no-GC lowers `logcount` through a scalar expansion and
  refuses the other two. **numbers 259 -> 243 (16 fixed), misc 52 -> 38 (15
  fixed), 0 regressed**, as a diff of failing test NAMES. Fully fixed:
  `LOGCOUNT.1-.8` + `ERROR.1/.2`, `RATIONALIZE.2` + `ERROR.1/.2/.3`,
  `MAX.2`/`MIN.2` (knock-on: the aux `+rational-most-*+` constants bind through
  `rational-safely` now). Surfaced, not regressed: the universe cascade
  (`LOGCOUNT.ERROR.3`, `RATIONALIZE.ERROR.4` -- §1 out of scope), the
  ratio->float conversion defect (`RATIONAL.1`, `RATIONALIZE.1/.3`, `/.12` --
  every collected rational probed exact; the mediant's round-trip guard +
  exact fallback pass under a correct conversion by construction), and
  `MISC.358` (now reaches `LDB-TEST`). Random churn (`BIGNUM.*` x8, `SQRT.17`,
  `RATIONAL.3`) unchanged. Two backend traps turned up by the work:
  `--no-gc`-shared lowerings must use fixed temporary names (an `MV_COUNTER`
  gensym hangs the `inferTypes` fixpoint forever), and WASM-GC big-denominator
  ratios trap fail-stop (`.kb/wasm-bignum.md`'s i32-component limit), which is
  what bounds the WASM pins to integers/`0.0`/ratios.
- **2026-09-15, `.todo/037` numbers Slice B (`rational`)** -- exact rational on
  all four backends (interpreter bit decomposition, JVM `_rational` over `_frat`
  + `_rat`, WASM-GC decomposition through `_int_new`/`_big_ash` + `_rat_div`,
  no-GC compile-time refusal). **numbers 274 -> 259 (15 fixed), misc 59 -> 52
  (7 fixed), 0 regressed**, as a diff of failing test NAMES. Three second
  reasons surfaced: float-vs-ratio `=` semantics (eight `*.17/*.18` + eight
  `BIGNUM.*` now FAIL -- `(= 1.0 (+ 1 tiny-ratio))` is T here, NIL in SBCL;
  the `rational` values probed exact), the ratio->float round trip
  (`RATIONAL.3`, via DECIMAL64 `doubleValue`), and the universe cascade
  (`RATIONAL.ERROR.4`, `*MINI-UNIVERSE*` unbound -- §1 out of scope).
  `SQRT.17` FAILs in both runs on different draws (the known `random` flake).
- **2026-09-15, `.todo/037` ash huge-count defect** -- `(ash a (min 0 a))` with
  `a = -2878148992` wrapped `(int) count` positive and built a monster bignum.
  Wide-first on the interpreter, the JVM (`_ash` + fused `_fxAsh`) and `--no-gc`
  (right-magnitude clamp); WASM-GC already clamped. Only out-of-int-range
  magnitudes saturate/signal; int-range counts keep the width-aware paths.
  **MISC.47/.48 + ASH.5 fixed, 0 regressed**, as a diff of failing test NAMES.
  Two findings: compare huge-count tests with `=` (printing the monster hangs
  quadratically); saturating at 64 for EVERY value regresses ASH.3 (a bignum
  `>> 70` keeps high bits -- saturation past 64 is fixnum-only). Also noted:
  `random`-drawn tests (ASH.3, SQRT.17) vary per run -- `ThreadLocalRandom` is
  time-seeded, so a flip needs a same-code rerun before it counts as a
  regression (verified via a clean-worktree baseline run).
- **2026-09-14, `.todo/037` numbers Slice A** -- `float-radix`, `logeqv`,
  `lognor`, `lognand`, `deposit-field`, expansion-only on all four backends.
  **numbers +106, misc +35, 0 regressed**, measured as a diff of failing test
  NAMES. Finding: `deposit-field` is NOT `dpb` (the suite's `dpb.lsp` checks
  `(logbitp (- i pos) newbyte)` where `deposit-field.lsp` checks `(logbitp i
  newbyte)` -- the first cut shipped the `dpb` spelling and `DEPOSIT-FIELD.1/.2`
  caught it). (The `MISC.47/.48` remainder noted here was the `ash` defect the
  2026-09-15 entry closes; other remainders live in `.todo/037`.)

- **2026-09-14, `.todo/029` (four loop slices)** -- uninterned `#:kw` spellings,
  NIL-as-no-binding, any-order numeric sub-clauses, the named loop's implicit
  block. **+113, 0 regressed** on the `iteration` chapter (63.6% -> 77.0%),
  measured as a diff of failing test NAMES. The scoping pass priced the chapter
  at 307 and split it eight ways first: the extra-`T` 74 belong to `.todo/213`,
  package-`being` ~18 to `.todo/156`, 8 `:BAD` to the do-family expanders --
  **a chapter census is not a mechanism census**. Two narrowings were measured,
  not reasoned: `#:being` needed the SAME strip in the filler's `plainName`
  (LOOP.16.64-.75 re-failed there after the keyword fix), and list `by` must
  evaluate once at entry AFTER the list form (LOOP.13.27/.28 pin it).

- **2026-08-12** -- `find-symbol`/`intern` answer the accessibility status as
  their second value. `symbols` 4.2% -> 58.4%, the largest single move the
  report has recorded. Three lowering bugs had to go first, all on
  `(find-symbol "CAR" 'common-lisp)`; `.kb/symbol-runtime-api.md`.
- **2026-08-15, `.todo/379`** -- the built-in seam wraps an escaping
  `IndexOutOfBounds` / `NegativeArraySize` / `Arithmetic` / `ClassCast` into a
  `LispEvalException`. STILL OPEN: `(elt (list 1 2) 5)`, `(nth -1 ...)` and
  `(coerce "abc" 'integer)` answer nil where CL signals -- a silent-nil family
  the seam cannot see.
- **2026-08-15, `.todo/380`** -- typed built-in errors. Still open:
  `reader-error`, `print-not-readable`, `storage-condition` and the
  `floating-point-*` four are not SEEDED, so a `(:use #:cl)` package's
  `'reader-error` can never match. Seed one WITH a signalling site, not before.
- **2026-09-08, `.todo/681`** -- a raw exception escaping a `deftest` is booked
  as that test's error, not as a lost form. Lost forms 2,229 -> 586, and the
  denominator grew by 1,770. **Any figure quoted before this date is on a
  different denominator.**
- **2026-09-11, `.todo/744`** -- `find-class` answers for the built-in class
  lattice. **+41 tests where the row priced 156**: once a class resolves, the
  test often fails for a second reason. The upper-bound rule above comes from
  here.
- **2026-09-12, `.todo/740` + `.todo/776`** -- the seventeen missing cons set /
  tree operators plus `:key` on the six `-if`/`-if-not` alist scans. `cons`
  59.0% -> 86.3%; **+514, 0 regressed, where the row priced 458** -- six
  operators recorded as "already present and correct" were losing ~90 tests to a
  first-class surface that took no keywords. The downward-blindness rule comes
  from here. `.kb/cons-set-and-tree-operators.md`.
- **2026-09-13, `.todo/796`** -- the `cl` package exports the standard's 978 names, whether
  or not rontolisp implements the operator (CLHS 11.1.2.1). **+187, 0 regressed**, exactly
  what the row priced it at and exactly the `symbols/cl-symbols.lsp` probe -- the one row so
  far where the census was neither blind up nor down, because the question is
  name-presence and is asked in ONE place. The tests that ask it through
  `(find-symbol x "CL")` (`functionp.4`, `function.4`, `cl-function-symbols.1`,
  `cl-macro-symbols.1`, the `pprint-dispatch` family) did NOT move: each is blocked by a
  missing OPERATOR, which is the distinction. `.kb/packages.md`, "The `cl` external list
  is the STANDARD's list". Still open beside it: `no-extra-symbols-exported-from-common-lisp`,
  which fails on `while` plus the invented `boole-3` .. `boole-16` -- `.todo/803`.
- **2026-09-13, `.todo/797`** -- `*read-suppress*` plus `read-from-string`'s stop index,
  which is the other half of every one of its tests. **+213, 0 regressed**; `reader`
  10.8% -> 47.8%, the second-largest single move the report has recorded. The row priced
  the pair at 161 + 67 = 228; 213 of that landed and the remaining 15 are
  `read-from-string`'s real lambda list (6) plus tests failing for a second reason.
  **The NARROWING is the finding.** A first cut published the index from the built-in on
  EVERY call, the `parse-integer` precedent: **+219 and -62**. The 62 were the SAME bug in
  both directions -- an index left on the `%mv-spill` channel by a call whose value was
  DISCARDED (a loop step, a `let` initform, `read`'s own internal parse) surfacing as the
  enclosing form's second value, in `reader`, `sequences`, `strings` and `pathnames`
  alike. Publishing from TAIL positions only (`lowerMvProducer` now runs
  `spillEscapingMvProducers` over a producer form it does not recognize) gave **+213 and
  zero regressions**. Over-claiming is a wrong answer even when the claim is a mechanism
  the codebase already had. `.kb/read-load-streams.md`, "`read-from-string` answers the
  STOP INDEX".
- **2026-09-12, `.todo/214`'s `subtypep` row** -- `subtypep` answers CL's
  valid-p as its second value, on all four backends. **+443, 0 regressed**
  (66.3% -> 68.6%), against the 112 this file used to price it at: the row
  counted only tests NAMED `SUBTYPEP.*` and missed the 160 `conditions`
  `*/IS-SUBTYPE-OF/*` and 136 `structures` tests that ask the same question
  through `subtypep*`.
  **The valid-p RULE is the real finding, and it was measured rather than
  reasoned.** A first cut claimed a decision for every pair outside the opaque
  compound heads: +486 and **-82**, the 82 being `SUBTYPEP.CONS.*` / `.REAL.*` /
  `.RATIONAL.*` / `.OR.*`, where the suite's `check-equivalence` accepts a nil
  valid-p and FAILS a false claim. Narrowing the claim to "a nil primary is a
  decision only between two plain type NAMES" gave +443 and zero regressions.
  Under-claiming is conforming; over-claiming is a wrong answer.
  `.kb/declarations-type-checks.md`.

## Reading caveat

463 top-level forms are still lost, so every chapter is measured
optimistically; `streams` (56) and `printer` (48) most of all. Closing a gap can
LOWER a chapter's rate by admitting the tests behind it -- that is progress, and
the reason the report keeps the lost-form column next to the rate.

`ansi-test/README.md`, "What the numbers are not": the suite tests full ANSI CL,
which rontolisp does not set out to be. A failing test is a statement about the
standard, not automatically a bug worth fixing. Section 3 above is that filter
applied.
