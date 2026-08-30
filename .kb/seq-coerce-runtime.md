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

## `search` is a different defect and was deliberately left alone

`search` is the worst number in the table on the interpreter (107 us for a
5-character needle in a 46-character haystack) and it did not move, because it
never reaches `coerce`: it is a Lisp-source prelude `defun`
(`LispPreludeLibrary.SOURCES`, `LispNames.SEARCH`), an O(n*m) double loop whose
inner body is two `(elt seq i)` calls and a `(funcall test ...)` per character
PAIR. Measured on the interpreter: `elt` 0.83 us, a `funcall` 0.33 us, so ~2.5 us
per character pair is the whole of it. The same source compiles to 0.5 us (JVM) /
1.4 us (WASM), so fixing it means a native interpreter arm or a cross-backend
primitive, neither of which the conversion work reaches. Carried as its own item
with these numbers.

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
- The behavior is pinned where it already was:
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
