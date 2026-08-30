# The interpreter converts a sequence in Java, not through an interpreted `map`

**Invariant: on the INTERPRETER, `(coerce x 'list)` / `'string` / `'vector` is a
Java conversion over the value's own buffer for every representation it can
answer identically, and DECLINES to the shared `expandCoerce` lowering for
everything else. The compile paths keep the expansion; both must answer the same
thing, including for the shapes the fast arm declines.**

Measured 2026-08-31 (Apple M4 Max, 100,000 iterations, one benchmark under a
machine-exclusive lock). This is a COST invariant, not a semantic one -- every
answer below was already right, and the fast arm exists to stop paying an
interpreted funcall per element for it.

## What was actually slow, and why `position` was the symptom

`.todo/590` reported `(position #\Space s)` on a 46-character string at 27.6 us
a call in the interpreter -- 90x `(char s 3)`, 40x a whole float parse through
`read-from-string`. It named generic-sequence dispatch as the suspect. The
dispatch is not where the time went.

`position` resolves in the interpreter through `LispEvaluator.evalCons`'s
`POSITION` case to `LispMacroExpander.expandPosition`, whose scan is wrapped in
`seqAsListForm` = a literal `(coerce seq 'list)` form. `expandCoerce`'s list arm
for a string is **`(map 'list #'identity s)`** -- an INTERPRETED funcall of
`#'identity` per character. That was 0.46 us per character, so:

| `(position #\Space <46-char string>)`, before | us |
| --- | ---: |
| the whole call | 28.8 |
| of which `(coerce s 'list)` | 23.5 |
| of which the scan (the space is at index 1, so TWO elements) | ~1.5 |

The operator built a 46-element list and then stopped scanning it at element 1.
Every generic sequence lowering funnels through the same two builders
(`seqAsListForm`, and `seqResultDispatchForm` which additionally rebuilds the
result with `(coerce res 'string)`), so `find`, `count`, `remove`, `substitute`,
`reduce`, `every`/`some`, `remove-duplicates` and the `sort` string wrap all paid
it -- `remove` and `substitute` paid it TWICE, in both directions, at 91 us and
94 us a call.

**So the fix is at the conversion, not at any operator.** One arm serves the
whole family.

## The seam

`LispEvaluator.evalCons`'s `COERCE` case now calls `evalSequenceCoerce`, which is
the declining-primitive shape `.kb/binary-sequence-io.md` records, in Java rather
than as a Lisp primitive:

1. `coerceSequenceTypeName` reads the result type SYNTACTICALLY, normalizing
   exactly as `expandCoerce` does (a package qualifier is dropped;
   `simple-string`/`base-string`/`simple-base-string` collapse to `STRING`,
   `simple-vector` to `VECTOR`). It answers only `LIST`, `STRING`, `VECTOR`;
   a computed designator, a compound spec, a float type and an unresolvable
   deftype name all answer null and go straight to the expansion, untouched.
2. The value form is evaluated ONCE.
3. `coerceSequenceFast` answers, or declines with null.
4. **A decline re-runs the shared expansion over the value it already
   evaluated**, re-quoted as `(coerce (quote <value>) <typeform>)`, so the value
   form is evaluated exactly once on either path and the expansion's answer --
   error, oddity and identity alike -- is unchanged.

The packed `(vector (unsigned-byte N))` result types are unaffected: the
pre-existing `ConcatenateForms.packedVectorCoerce` still runs first
(`.kb/concatenate-result-families.md`).

`sequenceElementsAsList` is the whole converter: `LispString` by CODE POINT
(`codePointCount`/`codePointAt`, so a supplementary character is ONE element),
rank-1 `LispArray` over `effectiveLength()`/`readFlat` (a fill pointer bounds it,
a displaced view reads through), rank-1 `LispFloatArray` over
`totalSize()`/`readFlat`, `LispIntVector` over `length()`/`elementAt`. Element
for element that is what `(aref v i)` over `(length v)` answers, which is what
the expansion's vector arm walks.

`Environment.seqAsList` -- the same conversion for the natively-registered
sequence functions (`#'position`, `reverse`, `count`, ...) -- was rebuilding the
whole Java `String` through `value()` on every call. It now reads the buffer slot
by slot, the same defect `.kb/string-index-cost.md` records for `charRef`.

## What each arm answers, and what it declines

Each arm reproduces its `expandCoerce` body exactly, oddities included:

- **`'list`** -- a `LispCons`/`LispNil` is itself (the `(listp x)` arm, a dotted
  list included); the four vector representations convert. Anything else
  DECLINES: a rank-2 array (whose `(length ...)` signals `not a sequence`), and
  a non-sequence, which the expansion answers **nil** for -- its `(length x)`
  falls through to the cons walk and finds none. That is not right, but it is
  what the operator has always done, so the fast arm reproduces it rather than
  improving on it.
- **`'string`** -- a `LispString` is itself; a list or a converted vector whose
  every element is a `LispChar` becomes a string. A NON-character element
  declines, and the expansion's `(map 'string #'identity ...)` answers what it
  always did (`(coerce '(1 2) 'string)` is `"12"`).
- **`'vector`** -- a list or a string is filled into a fresh rank-1 `LispArray`
  (what `(make-array (length l))` + `aset` built); anything else is the
  IDENTITY, exactly as `coerceToVectorBody`'s else arm is, so this arm never
  declines. `(coerce 5 'vector)` is `5` and a packed float array coerced to
  `'vector` is itself.

## Measured

Interpreter, Apple M4 Max, 100,000 iterations over the 46-character string
`"v -3.4101800e-003 1.3031957e-001 2.1754370e-002"`, us per call. Before and
after taken in the same locked benchmark, three alternating rounds (spread under
3%); the first five rows are the unchanged reference shapes.

| form | before | after | |
| --- | ---: | ---: | ---: |
| the `dotimes` loop itself | 0.49 | 0.57 | |
| `(char *line* 3)` | 0.46 | 0.45 | |
| `(subseq *line* 2 17)` | 0.47 | 0.42 | |
| `(read-from-string "-3.4101800e-003")` | 0.72 | 0.65 | |
| `(parse-integer *line* :start 2 :junk-allowed t)` | 6.60 | 6.56 | |
| `(coerce *line* 'list)` | 23.5 | **0.27** | 87x |
| `(coerce *line* 'vector)` | 47.5 | **0.76** | 63x |
| `(coerce <46 chars> 'string)` | 71.7 | **0.42** | 171x |
| `(position #\Space *line*)` | 28.8 | **3.95** | 7.3x |
| `(position #\@ *line*)` -- a full miss | 49.5 | 23.4 | 2.1x |
| `(position #\Space *line* :from-end t)` | 50.5 | 24.2 | 2.1x |
| `(position #\Space *line* :start 3)` | 34.1 | 9.01 | 3.8x |
| `(position #\Space *line* :end 20)` | 27.6 | 3.45 | 8.0x |
| `(position #\Space *line* :test #'char=)` | 27.2 | 2.85 | 9.5x |
| `(position #\Space *line* :key #'identity)` | 27.4 | 2.84 | 9.6x |
| `(position #\Space <same as a vector>)` | 26.3 | 2.55 | 10.3x |
| `(position 3 '(1 2 ... 10))` -- a LIST, no conversion | 3.38 | 3.06 | 1.1x |
| `(find #\Space *line*)` | 27.5 | 2.78 | 9.9x |
| `(count #\Space *line*)` | 39.8 | 12.9 | 3.1x |
| `(remove #\Space *line*)` | 90.9 | 18.7 | 4.9x |
| `(substitute #\_ #\Space *line*)` | 93.9 | 19.2 | 4.9x |
| `(reduce #'(lambda (a b) a) *line*)` | 27.1 | 3.23 | 8.4x |
| `(search "e-001" *line*)` | 107.4 | 106.6 | **1.0x** |

The last row is the one this change could not reach; it got its own arm the next
day (see "`search` and `mismatch` are a different defect" below), and is 0.50 us
now.

`position` on a 46-character string is now 8.8x `(char s 3)` where it was 63x.
Every keyword arm improves, because the keywords ride on the same scan and it was
the conversion that was slow -- the `:test`/`:key`/`:start`/`:end`/`:from-end`
arms are not a separate path and no arm was special-cased.

**What did NOT move, and why the residue is the interpreter, not the operator.**
A FULL-SCAN `position` (the miss, and `:from-end`, which never returns early)
still pays 23 us, which is 0.5 us per element for the expansion's interpreted
`do` loop -- `(atom cur)`, `(eql item (car cur))`, `(+ idx 1)`, `(cdr cur)`, one
eval dispatch each. That is the tree-walking interpreter's per-node cost, the
same cost `(position 3 '(1 2 ... 10))` over a plain LIST has always paid and
which this change does not touch. A list argument does not convert, so it neither
gained nor lost.

## The compile paths were never the problem, and did not move

Same program, same machine, same locked run, us per call:

| form | JVM | | WASM p1 | | `--component` | |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| | before | after | before | after | before | after |
| `(char *line* 3)` | 0.15 | 0.14 | 0.01 | 0.01 | 0.01 | 0.01 |
| `(coerce *line* 'list)` | 0.23 | 0.21 | 0.71 | 0.69 | 0.73 | 0.68 |
| `(position #\Space *line*)` | 0.59 | 0.61 | 0.72 | 0.71 | 0.74 | 0.70 |
| `(position #\@ *line*)` | 2.59 | 2.47 | 0.93 | 0.92 | 0.96 | 0.92 |
| `(find #\Space *line*)` | 0.37 | 0.36 | 0.73 | 0.70 | 0.79 | 0.70 |
| `(count #\Space *line*)` | 2.07 | 2.01 | 1.01 | 0.91 | 0.96 | 0.90 |
| `(search "e-001" *line*)` | 0.51 | 0.73 | 1.50 | 1.39 | 1.47 | 1.40 |
| `(remove #\Space *line*)` | 7.73 | 6.81 | 7.11 | 6.92 | 7.41 | 7.08 |

The change is interpreter-only, so the two columns of each pair are the same
build twice; the spread is measurement noise (the JVM `search` row is JIT
scheduling). **A compiled `.class` and both WASM backends were already 25-70x
cheaper than the interpreter on this family, which is why nothing was added to
them** -- a declining primitive in front of the shared expansion would have cost
every wasm module bytes (`position` is 449 B a site, `.kb/sequence-op-runtimes.md`)
to fix a problem those backends do not have.

## `search` and `mismatch` are a different defect, and got their own scan

`search` was the worst number in the table above and did not move, because it
never reaches `coerce`. Fixed the next day (2026-08-31) with the arm this section
records; `mismatch`, measured at the same time, has the identical shape and the
identical cost, and is served by the same arm.

**Why they are not the conversion's problem.** Both are Lisp-source prelude
`defun`s (`LispPreludeLibrary.SOURCES`, `LispNames.SEARCH` / `MISMATCH`) on every
backend, and neither uses `seqAsListForm`: they index both operands with
`(elt seq i)` and compare with `(funcall test ...)`, so on the interpreter each
element PAIR costs two `elt` calls plus a funcall. Measured: `elt` 1.0 us,
`char` 0.45, a funcall 0.33 -- ~2.5 us a pair, which multiplied by `search`'s
O(n*m) double loop is the whole 104 us. There is nothing pathological in the
algorithm; it is the tree-walking interpreter's per-node cost times n*m. The same
source compiles to 0.9 us (JVM) and 1.4 us (both WASM).

### The seam

`LispEvaluator.evalConsRareOperator` gained a `SEARCH` / `MISMATCH` case calling
`evalSequenceScan`, which does exactly what the ordinary call path does -- resolve
the function, then evaluate the arguments, in that order -- and puts
`SequenceScanFast` in front of the `apply`. A decline costs one pass over the
argument array and then applies the same function to the same values, so the
evaluation order, the number of evaluations and every error are unchanged.

The arm serves only what it can prove answers identically:

- **The comparison must be `eql`** -- an absent `:test` (the prelude's own
  default), an explicit `#'eql`, or `#'char=` when BOTH operands are strings,
  where every element is a character and char= is the code-point equality `eql`
  already answers for a `LispChar`. `:key` non-nil, a user function, a lambda and
  an explicit `:test nil` all decline; serving them would mean an interpreted call
  per element, which is the cost this exists to avoid.
- **Both operands must be sequences `(length x)` measures the way the arm does**
  -- a string (by code point), a PROPER list (materialized once into a
  `LispVal[]`, which also removes the `nth`-per-element O(n^2*m) a two-list
  `search` had), a rank-1 `LispArray` (`effectiveLength`/`readFlat`, so a fill
  pointer bounds it and a displaced view reads through), a `LispIntVector`, a
  rank-1 `LispFloatArray`. A dotted list, a rank-2 array and a non-sequence
  decline, so the prelude keeps owning what it answers for them -- including
  `(search "ab" 5)` being NIL rather than an error, the same `(length x)` oddity
  this file records for `coerce`.
- **Every bounding index must be inside its sequence, with start <= end.** What
  the prelude does outside that depends on which `elt` call it reaches first
  (`(search "ab" "xab" :end2 99)` is 1, not an error, because the match is found
  before the loop walks off the end), so the arm never guesses. An END of `nil`
  IS the default (the body or-defaults it); a START of `nil` is not (the lambda
  list binds it and the arithmetic signals) -- those two are handled apart.
- **`mismatch` with `:from-end` declines.** The prelude accepts the keyword and
  IGNORES it, scanning forward -- `(mismatch "abcd" "xbcd" :from-end t)` is 0 on
  all four backends where CLHS says 1. That is a DELIBERATE, documented deviation
  (`doc/{en,ja}/reference/functions/mismatch.md` marks it "Lite"), not a defect
  found here; declining keeps it in the one place that owns it instead of
  spreading it to a second implementation.

**A user redefinition takes the call back WHOLE.** The lazy prelude loader already
honours a `(defun search ...)` by never loading its entry over one; the arm
honours a redefinition made AFTERWARDS by serving only while the name still
resolves to the object the loader installed. `LispEvaluator.loadPreludeDefinition`
-- now the single load site both callers use -- records that object in
`preludeDefinitions`. Without this the fast shapes would silently ignore a
redefinition the declined shapes honour, which is worse than either alone.

### Measured

Interpreter, Apple M4 Max, 20,000 timed iterations after a 5,000-iteration warmup,
one locked benchmark, us per call. Haystack is the same 46-character string as the
table above; the first three rows are unchanged reference shapes. (The
100,000-iteration baseline the previous day read 106.6 / 11.9 / 130.0 / 18.3 for
rows 4, 5, 6 and 16 -- the same numbers within noise.)

| form | before | after | |
| --- | ---: | ---: | ---: |
| the `dotimes` loop itself | 0.95 | 1.00 | |
| `(char *line* 3)` | 0.50 | 0.45 | |
| `(elt *line* 3)` | 1.05 | 1.00 | |
| `(search "e-001" *line*)` -- a hit at 27 | 104.4 | **0.50** | 209x |
| `(search "v " *line*)` -- a hit at 0 | 11.7 | **0.35** | 33x |
| `(search "zzzz" *line*)` -- a full miss | 136.2 | **0.50** | 272x |
| `(search "" *line*)` -- an empty needle | 7.70 | **0.30** | 26x |
| `(search "e-0" *line* :from-end t)` | 157.3 | **0.55** | 286x |
| `(search "e-001" *line* :start2 20)` | 41.6 | **0.40** | 104x |
| `(search "e-001" *line* :end2 40)` | 107.5 | **0.40** | 269x |
| `(search "xxe-001yy" *line* :start1 2 :end1 7)` | 111.0 | **0.40** | 277x |
| `(search "e-001" *line* :test #'char=)` | 108.9 | **0.55** | 198x |
| `(search "E-001" *line* :key #'char-upcase)` -- DECLINED | 113.9 | 116.4 | **1.0x** |
| `(search <needle> <line>)`, both as VECTORS | 109.3 | **0.70** | 156x |
| `(search "e-001" <line as a vector>)` -- mixed | 107.3 | **0.75** | 143x |
| `(search '(3 4) '(1 2 ... 10))` -- two LISTS | 19.1 | **0.50** | 38x |
| `(mismatch *line* <differs at the last character>)` | 110.1 | **0.50** | 220x |
| `(mismatch *line* *line*)` -- equal | 110.2 | **0.45** | 245x |
| `(mismatch "x" *line*)` -- differs at 0 | 8.15 | **0.20** | 41x |
| `(mismatch '(1 ... 10) '(1 ... 11))` | 31.3 | **0.40** | 78x |

`search` on this string is now 1.1x `(char s 3)` where it was 209x. The one row
that does not move is the declined one, and that is the design: `:key` and a
user `:test` still run the prelude at its old cost.

### The compile paths were never the problem, and nothing was added to them

Same program, same locked run, us per call. The change is interpreter-only, and
provably so: **both wasm modules are byte-identical between the two jars**
(`cmp`), and the two `.class` files differ only in the class NAME the benchmark
gave them, so each pair below is one artifact measured twice and the spread is
JIT/measurement noise.

| form | JVM | | WASM p1 | | `--component` | |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| | before | after | before | after | before | after |
| `(char *line* 3)` | 0.15 | 0.15 | 0.00 | 0.00 | 0.00 | 0.00 |
| `(search "e-001" *line*)` | 0.90 | 1.15 | 1.40 | 1.40 | 1.40 | 1.40 |
| `(search "zzzz" *line*)` | 2.20 | 1.70 | 1.70 | 1.70 | 1.70 | 1.75 |
| `(search "e-0" *line* :from-end t)` | 2.20 | 1.95 | 2.20 | 2.20 | 2.20 | 2.25 |
| `(search "E-001" *line* :key #'char-upcase)` | 1.60 | 2.10 | 2.30 | 2.30 | 2.25 | 2.35 |
| `(search '(3 4) '(1 2 ... 10))` | 0.45 | 1.35 | 0.15 | 0.15 | 0.15 | 0.15 |
| `(mismatch *line* <differs at the last char>)` | 0.60 | 0.55 | 1.55 | 1.55 | 1.50 | 1.55 |

**A cross-backend primitive was rejected for the same reason `position`'s was.**
Three of the four backends were already 75-150x cheaper than the interpreter here,
and a declining primitive in front of the prelude body would cost every wasm
module bytes to fix a problem they do not have -- `search` is 15 bytes a site
today (`.kb/sequence-op-runtimes.md`) precisely because the prelude `defun` is
already a shared callee. Replacing the prelude entry with a native
`Environment` built-in was rejected too: the prelude loads only when the name is
unresolved, so a native registration is a REPLACEMENT, not a fast path, and the
interpreter would then hold a second full implementation of the keyword set that
has to agree with the compile paths in every corner. The declining arm holds only
the eql scan.

### The list arm fixes a real complexity, not just a constant

`(elt list i)` lowers to `(nth i list)`, an O(i) walk from the head, so the
prelude's inner loop over two lists is O(n^2*m) and `mismatch` over two lists is
O(n^2) -- the same defect `.kb/sequence-op-runtimes.md` records fixing in
`replace`'s list SOURCE arm. Materializing each list into a `LispVal[]` once makes
the served path O(n*m) / O(n). The compile paths still have the quadratic walk;
they still have it, and it bites at size: `(search '(3 5) <n-element list>)` on
wasm-GC is 0.05 / 0.20 / 0.70 / 2.65 ms for n = 250 / 500 / 1000 / 2000 -- a clean
4x per doubling -- against **0.06 ms** for the interpreter's arm at n=2000, so a
compiled program is now 44x (WASM) to 740x (JVM) SLOWER than the interpreter on a
long-list `search`. That is not this arm's to fix: the honest repair is the
`nthcdr`/`cdr` cursor rewrite in the PRELUDE SOURCE, which moves all four backends
at once, and it is carried as `.todo/593` with the ladder above. Nothing measured
today passes a long list (`uiop-utility.lisp`'s `frob-substrings`,
`cffi-rontolisp.lisp` and `examples/db/database-url.lisp` all search strings), so
it is a latent complexity bug rather than a profile.

## Re-evaluation triggers

- **A full-scan sequence operator on the interpreter is now bounded by the
  interpreted `do` loop, ~0.5 us per element.** If that becomes the profile, the
  answer is the evaluator's per-node cost (the expansion is rebuilt by
  `expandPosition` on EVERY evaluation, ~1.5 us of skeleton before a single
  element is touched), not another arm on `coerce`.
- **The fast arm must stay a strict subset of the expansion.** If `expandCoerce`
  grows a representation or changes an arm, `coerceSequenceFast` has to follow or
  start declining it -- the ci-spec case below is what catches the drift, because
  it runs the same program on the interpreter (fast arm) and on three backends
  that keep the expansion.
- **`LispFloatArray` is served for `'list` but is the identity for `'vector`,**
  matching the expansion. If `coerceToVectorBody` ever learns to rebuild a packed
  array, this arm must learn it in the same commit.
- **`SequenceScanFast` must stay a strict subset of the prelude `defun`.** If
  `LispPreludeLibrary`'s `search`/`mismatch` source changes -- a bound, an
  argument order, the `:from-end` scan direction -- the arm has to follow it or
  start declining the shape, in the same commit. The ci-spec case below is what
  catches the drift, because the interpreter runs the arm and three backends run
  the defun.
- **The declined shapes still cost 110-120 us.** `:key`, a user `:test` and an
  out-of-range bound run the prelude at its old price. Serving them means calling
  back into `apply` per element, which is a different trade (~0.4 us a pair
  rather than 2.5, not 0.01) and a much wider agreement surface; measure a real
  consumer before taking it.
- **`mismatch` ignores `:from-end` on ALL FOUR backends**, documented as a "Lite"
  deviation. If it is ever made CLHS-correct, that is a prelude-SOURCE change --
  it moves every backend at once -- and the arm's decline can then be lifted in
  the same commit.

## Pinning tests

- `src/test/resources/ci-spec.yaml` -- `sequence-coerce-across-representations`,
  run on all four backends by `CiSpecE2eTest`: the served representations
  (string, a code point above the BMP, a fill-pointered string and general
  vector, packed integer and float arrays) and the declined ones (a
  non-character element on the way to a string, a non-sequence to `'vector` and
  to `'list`) in one program, with the operators that reach the conversion
  through `seqAsListForm` below them.
- `LispEvaluatorTest.theNativeSequenceCoerceArmAnswersEveryRepresentationTheExpansionDoes`
  and `#theNativeSequenceCoerceArmDeclinesEverythingItCannotAnswerIdentically` --
  both directions of the decline, the alias collapsing, and that the value form
  is evaluated ONCE whichever arm answers.
- `JvmLispCompilerTest.compileSequenceCoerceAnswersTheSameForEveryRepresentation`
  and `WasmLispCompilerIntegrationTest.sequenceCoerceAnswersTheSameForEveryRepresentation`
  -- the same answers from the backends that keep the expansion, which is where a
  divergence would hide.
- `src/test/resources/ci-spec.yaml` --
  `search-and-mismatch-across-representations`, all four backends: every
  representation and keyword the scan arm serves, and the ones it declines
  (`:key`, `#'char-equal`, an out-of-range `:end2`, `start1 > end1`, `mismatch
  :from-end`) in one program.
- `LispEvaluatorTest.theNativeSearchAndMismatchArmAnswerWhatThePreludeDefunAnswers`
  and `#theNativeSearchAndMismatchArmDeclineEverythingTheyCannotAnswerIdentically`
  -- every case checked BOTH against a literal expectation and against
  `(funcall #'search ...)`, which applies the prelude `defun` directly and never
  reaches the arm; plus the user-redefinition guard in both directions and that
  the operands are evaluated ONCE whichever arm answers.
- `JvmLispCompilerTest.compileSearchAndMismatchAnswerTheSameAsTheInterpretersNativeArm`
  and `WasmLispCompilerIntegrationTest.searchAndMismatchAnswerTheSameAsTheInterpretersNativeArm`
  -- the same program on the backends that run the `defun`. The wasm one must use
  `compileAndRunPrelude`, not `compileAndRun`: without the prelude splice the
  module has no `search` at all and traps.
- The behavior is pinned where it already was:
  `LispEvaluatorTest.evalCopyTreeAndSearch`,
  `LispEvaluatorTest.coerceConvertsBetweenListVectorAndString` /
  `#coerceAcceptsAComputedResultType`, `JvmLispCompilerTest.compileCoerceConversions`,
  and the sequence cases of all three per-backend suites.

## Related

- [[seq-conversion-runtime]] -- the COMPILE paths' answer to the same builders:
  the `%seq-to-list`/`%seq-to-string`/`%seq-to-vector` trio, shared for SIZE
  rather than speed. That file and this one are the two halves of one conversion.
- [[binary-sequence-io]] -- the declining-primitive protocol this arm follows.
- [[string-index-cost]] -- why reading a string by slot rather than through
  `value()` matters, and the compile backends' own instance of the same defect.
- [[sequence-op-runtimes]] -- `replace`/`fill`/`map-into`, the operators that do
  NOT go through `coerce`, and the per-site byte budgets that argue against
  putting a primitive on the compile paths here.
