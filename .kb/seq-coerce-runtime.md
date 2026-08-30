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
the served path O(n*m) / O(n). **The three compile paths kept the quadratic
walk until hours later the same day**, when the prelude source itself grew a cons
cursor; that is the section below, and it is what makes the invariant at the top
of this file hold at SIZE as well as at n = 46.

## The prelude bodies walk a list with a cursor, not with `elt`

Measured 2026-08-31, hours after the arm above landed.

`search` and `mismatch` indexed BOTH operands with `(elt seq i)`, which for a
list is an `nth` walk from the head. The interpreter's arm hid it for the shapes
it serves; the JVM and both WASM backends run the `defun`, so a two-list `search`
was O(n^2*m) and a two-list `mismatch` O(n^2) there. At n = 2000 a compiled
program was 44x (WASM) to 740x (JVM) SLOWER than the interpreter on a long-list
`search`, which is the wrong way round and was entirely the `nth` walk.

Both bodies now seed a cons cursor and read through it. **The cursor is not a
`(null cell)` STOP** -- the `replace` list-source arm could take one, and changed
an invalid call's answer doing it (`.kb/sequence-op-runtimes.md`); this pair
cannot, because `SequenceScanFast` DECLINES every out-of-range bound precisely so
these bodies keep owning what they answer there. The read is therefore

```lisp
(if (consp c) (prog1 (car c) (setq c (cdr c))) (elt seq i))
```

-- the `map-into` cursor shape (`LispMacroExpander.readElement`) with the advance
folded into the read. A non-list operand pins a nil cursor and keeps indexing; a
list whose cursor has run out -- past an out-of-range bound, or onto a dotted
tail -- falls back to the very `elt` call the body used to make, answer and error
alike. `search` seeds the needle cursor once (its `start1`/`end1` window never
moves) and the haystack cursor advances one `cdr` per OUTER position, both copied
into the inner walk, which restarts at every position.

**Folding the advance into the read is what makes it free for a string.** A
separate `(if (consp c) (cdr c) c)` step form costs a SECOND `consp` call per
element, and on the interpreter's declined path -- a string, where the cursor
never fires -- that is the whole regression. Three shapes, interpreter, 2,000
timed calls after 2,000 warm-up, `(search "e-001" <46-char string>)` /
`(search "zzzz" ...)` / `(search '(3 5) <2000-element list>)`, ms for the batch:

| shape | string hit | string miss | 2000-element list |
| --- | ---: | ---: | ---: |
| the `elt`-indexed body | 230 | 280 | 223 |
| cursor, separate `consp` advance | 290 | 379 | 147 |
| cursor, `prog1` advance (**taken**) | 246 | 325 | 136 |
| `prog1` plus a hoisted list flag | 242 | 319 | 146 |

The hoisted flag buys nothing the `prog1` has not already bought and duplicates
the `elt` form, so the two-line shape wins.

### The ladder

Apple M4 Max, one locked run, before and after in the same acquisition. Haystack
is `(mod i 7)` over n elements, needle `'(3 5)` -- which never occurs, so every
outer position is attempted. 200 timed iterations after a 200-iteration warm-up,
**ms per call**. The wasm-GC backend has no JIT, so its ladder is the proof:
doubling n used to QUADRUPLE the time and now DOUBLES it.

| n | 250 | 500 | 1000 | 2000 | 4000 |
| --- | ---: | ---: | ---: | ---: | ---: |
| WASM p1 `(search '(3 5) ...)` before | 0.043 | 0.158 | 0.603 | 2.29 | 8.80 |
| WASM p1 `search` **after** | 0.008 | 0.015 | 0.028 | **0.055** | **0.103** |
| WASM p1 `(mismatch <n> <itself>)` before | 0.068 | 0.255 | 1.00 | 3.98 | 15.4 |
| WASM p1 `mismatch` **after** | 0.005 | 0.008 | 0.018 | **0.035** | **0.073** |
| `--component` `search` before / after | 0.048 / 0.010 | 0.158 / 0.015 | 0.59 / 0.028 | 2.33 / 0.053 | 8.83 / 0.105 |
| `--component` `mismatch` before / after | 0.068 / 0.003 | 0.253 / 0.010 | 1.00 / 0.018 | 3.97 / 0.035 | 15.4 / 0.068 |
| JVM `.class` `search` before | 0.063 | 0.198 | 0.71 | 3.10 | 12.6 |
| JVM `.class` `search` **after** | 0.015 | 0.018 | 0.050 | **0.005** | **0.013** |
| JVM `.class` `mismatch` before | 0.095 | 0.34 | 1.16 | 4.77 | 20.7 |
| JVM `.class` `mismatch` **after** | 0.015 | 0.015 | 0.035 | **0.010** | **0.020** |
| interpreter, the native ARM (unchanged) | 0.018 | 0.013 | 0.033 | 0.033 | 0.055 |

Every after-row doubles with n where the before-row quadrupled; the JVM's
after-rows fall as n grows because the JIT gets more iterations to compile, which
is what a linear loop looks like at this scale. At n = 4000 the compile paths are
85x (WASM) to 1,000x (JVM) faster than they were, and a compiled program is once
again FASTER than the interpreter on this path instead of 44-740x slower.

The `defun` reached directly with `(funcall #'search ...)`, 20 timed iterations,
ms per call at n = 2000: interpreter `search` 11.5 -> **8.0**, `mismatch` 10.8 ->
**5.8** (the residue is the tree-walking interpreter's per-node cost, not the
walk); JVM 2.95 -> **0.02**; WASM p1 2.30 -> **0.05**.

### What it costs

**The declined path pays 12-15%.** The interpreter's arm declines `:key`, a
non-`eql` `:test` and every out-of-range bound into these bodies, and a string
operand there now pays one `consp` per element per operand that it did not.
Interpreter, us per call:

| form | before | after | |
| --- | ---: | ---: | ---: |
| `(search "E-001" <46 chars> :key #'char-upcase)` -- declined | 120 | 138 | +15% |
| `(funcall #'search "e-001" <46 chars>)` | 114 | 130 | +14% |
| `(funcall #'search "zzzz" <46 chars>)` -- a full miss | 151 | 171 | +13% |
| `(funcall #'mismatch <46 chars> <itself>)` | 112 | 126 | +12% |
| `(funcall #'search '(3 4) '(1 2 ... 10))` -- a short list | 20.8 | 21.5 | +3% |
| `(search "e-001" <46 chars>)` -- the ARM, untouched | 1.0 | 1.0 | -- |

The same rows on the three compile paths are 1.5-2.0 us before and after: the
cursor branch is free once compiled. That trade -- 12-15% on an interpreter path
that already costs 100+ us, for a complexity class on three backends -- is why
the fallback was kept rather than replaced with a `(null cell)` stop.

**The bodies grew; the SITES did not.** wasm-GC, one call site:

| program | wasm | `--optimize=size` | JVM `.class` |
| --- | ---: | ---: | ---: |
| `(print 1)` | 496 -> 496 | 496 -> 496 | 3,004 -> 3,004 |
| one `search` | 26,145 -> 26,901 | 24,443 -> 25,077 | 40,002 -> 41,534 |
| one `mismatch` | 25,732 -> 26,426 | 24,385 -> 24,953 | 38,821 -> 40,266 |
| both | 32,512 -> 33,962 | 29,965 -> 31,167 | 45,526 -> 48,334 |

+634 B of `--optimize=size` wasm for `search`, +568 for `mismatch`, and **0 for a
program that calls neither**. The marginal cost of a SECOND `search` site is 42 B
at `--optimize=size` before and after -- unchanged, because the `defun` is a
shared callee (`.kb/sequence-op-runtimes.md`'s per-site table still reads 15 B
for the argument-free shape it measures). This is the opposite sign to
`replace`'s list-source cursor, which SHRANK its helper: that change replaced the
`elt` loop, this one adds a branch in front of it and keeps it.

Nothing shipped passes a long list -- `uiop-utility.lisp`'s `frob-substrings`,
`cffi-rontolisp.lisp`, `examples/db/database-url.lisp` and
`bench-report/programs/string.lisp` all search STRINGS -- so this was a latent
complexity bug rather than a profile, and the numbers above are what a consumer
that does pass one would have paid.

## The rest of the `elt`-per-element family

Measured 2026-08-31, hours after the prelude cursor above. `.todo/594` carried
two more sites and asked whether the family had more; the survey found two more
again. **Five sites, one cursor.** The prelude is not where the family lived --
four of the five are lowerings in `LispMacroExpander` and one is a native
interpreter builtin, and each was quadratic on exactly the half of the world the
one beside it was fine on.

| the site | who ran the quadratic loop | the indexed read it made |
| --- | --- | --- |
| `lowerInitialContentsMakeArray` (rank 1) | the 3 compile paths | `(elt contents i)` |
| `buildNestedInitialContentsFillLevel` (rank >= 2) | the 3 compile paths | `(elt <this level's seq> idx)`, once per LEVEL |
| `replaceDispatch`'s list-DESTINATION arm | all four | `(elt source (+ start2 k))` |
| `expandMap`, per operand | all four | `(nth i s)` |
| `Environment`'s native `replace`, source side | the INTERPRETER only | `sequenceRef(source, start2 + k)` |

The first four take the SAME read the prelude bodies took --
`(if (consp c) (prog1 (car c) (setq c (cdr c))) <the indexed read>)`, spelled once
as `LispMacroExpander.readElementAdvancing` / `cursorRead` -- and for the same
reason: the fallback is what reproduces every answer and every error, so nothing
had to be re-derived about NIL past a proper list, a signal past a dotted tail,
or a negative index. The fifth is Java (`Environment.SequenceSourceCursor`) and
keeps `sequenceRef` for every non-list representation, so a string, a general
array and a packed vector read exactly the slot they read before.

**No site took a `(null cell)` stop.** `replace`'s ARRAY-arm list source has one
and changed what an invalid call answers doing it
(`.kb/sequence-op-runtimes.md`); none of these five could afford that, and the
differential below is what proves none of them took it by accident.

**Two of the five are one cursor per something, not one per call.** The rank >= 2
fill binds a cursor per DIMENSION LEVEL, re-seeded on every iteration of the
level above -- which is what a nested list of lists needs, since each row is a
fresh list. `map` binds one per OPERAND, all advanced by the same read, since
they all sit at index `i`.

### The ladders

Apple M4 Max, one locked acquisition, before and after in the same run, two
rounds, the faster taken. Elements are `(mod i 7)`. **ms per call.** The internal
clock is millisecond-resolution on every backend, so the AFTER rows come from a
third pass at 8x the iteration count -- a linear ladder is otherwise read off a
handful of ticks.

`(make-array n :initial-contents <n-element list>)` -- the interpreter never runs
this (native `make-array`), which is why the inversion was so wide:

| n | 250 | 500 | 1000 | 2000 | 4000 |
| --- | ---: | ---: | ---: | ---: | ---: |
| WASM p1 before | 0.036 | 0.135 | 0.519 | 2.93 | **16.6** |
| WASM p1 **after** | 0.0035 | 0.0067 | 0.0138 | 0.0296 | **0.062** |
| `--component` before / after | 0.035 / 0.0034 | 0.133 / 0.0067 | 0.520 / 0.0138 | 2.92 / 0.0294 | 16.6 / **0.062** |
| JVM `.class` before | 0.066 | 0.217 | 0.791 | 3.01 | **11.8** |
| JVM `.class` **after** | 0.017 | 0.034 | 0.067 | 0.133 | **0.266** |
| interpreter, native (unchanged) | 0.0015 | 0.0022 | 0.0041 | 0.0083 | 0.016 |

**268x on WASM and 44x on the JVM at n = 4000**, and every after-row doubles
where the before-row quadrupled. A compiled program was 1,100x (WASM) slower
than the interpreter on this call; it is now 3.9x, which is the constant, not the
class.

The rank >= 2 fill, `(make-array '(R 4) :initial-contents <R rows of 4>)`:

| R | 250 | 500 | 1000 | 2000 |
| --- | ---: | ---: | ---: | ---: |
| WASM p1 before | 0.049 | 0.163 | 0.570 | **3.56** |
| WASM p1 **after** | 0.0131 | 0.0260 | 0.0514 | **0.1025** |
| JVM before / after | 0.163 / 0.082 | 0.416 / 0.161 | 1.16 / 0.325 | 3.76 / **0.646** |

`(replace <4000-element array> <n-element list>)` -- the mirror, quadratic on the
INTERPRETER and linear on the three compile paths since todo-413:

| n | 500 | 1000 | 2000 | 4000 |
| --- | ---: | ---: | ---: | ---: |
| interpreter before | 0.141 | 0.626 | 2.63 | **10.6** |
| interpreter **after** | 0.0023 | 0.0042 | 0.0083 | **0.0169** |
| JVM (unchanged) | 0.0015 | 0.0028 | 0.0051 | 0.0125 |
| WASM p1 (unchanged) | 0.0069 | 0.0138 | 0.0295 | 0.0625 |

**627x at n = 4000**, and the interpreter goes from 850x SLOWER than the compiled
program to 3.7x faster than WASM.

`(replace <4000-element list> <4000-element list>)` -- a list on BOTH sides, which
was quadratic everywhere at once, and `(map 'list #'1+ <n-element list>)`:

| shape at n = 4000 | interpreter | JVM | WASM p1 | `--component` |
| --- | ---: | ---: | ---: | ---: |
| list-into-list `replace` before | 10.6 | 11.35 | 16.5 | 16.6 |
| list-into-list `replace` **after** | **0.023** | **0.019** | **0.051** | **0.051** |
| `(map 'list #'1+ ...)` before | 12.7 | 11.3 | 22.2 | 22.2 |
| `(map 'list #'1+ ...)` **after** | **2.21** | **0.491** | **0.069** | **0.069** |
| `(map 'vector #'1+ <list>)` before / after | 12.9 / 2.24 | 11.3 / 0.503 | 22.5 / **0.123** | 22.5 / 0.122 |
| `(mapcar #'1+ ...)`, for scale | 0.059 | 0.028 | 0.027 | 0.027 |

`map`'s ladder over a list, WASM p1: 0.131 / 0.510 / 2.52 / **22.2** before,
0.0051 / 0.0104 / 0.0356 / **0.069** after -- **320x**, and the `'vector` result
type routes through the `'list` walk, so it moved with it. The interpreter's
after-row is 2.2 ms rather than 0.069 because what is left there is the
tree-walking interpreter's own per-node cost, the same residue `search` left; it
is linear now (0.274 / 0.546 / 1.10 / 2.21 doubles cleanly) where it was not.

### What it costs

**A non-list operand pays one `consp` per element for a cursor it never uses --
and the honest number needs its own program.** In the ladder above every row is
inlined into ONE toplevel method, and there the JVM's declined rows move by far
more than this change does: `(map 'vector #'1+ <vector>)` reads 0.105 -> 0.645
and `(mapcar #'1+ ...)` reads 0.028 -> 0.252, and **`mapcar` is not touched by
this change at all**. Growing one row's inline body moves the JIT's decisions for
its neighbours. Re-measured with each row in its own `defun` and the whole thing
in its own program, ms per call at n = 4000, before -> after:

| declined row | interpreter | JVM | WASM p1 |
| --- | --- | --- | --- |
| `(map 'vector #'1+ <vector>)` | 1.807 -> **2.168** (+20%) | 0.0400 -> 0.0385 | 0.1485 -> 0.1500 (+1%) |
| `(make-array 4000 :initial-contents <vector>)` | 0.0090 -> 0.0090 (native) | 0.0025 -> 0.0020 | 0.1120 -> 0.1145 (+2%) |
| `(replace <array> <vector>)` | 0.0095 -> 0.0095 | 0.0110 -> 0.0095 | 0.0770 -> 0.0775 |
| `(replace <string> <string>)` | 0.0015 -> 0.0015 | 0.0075 -> 0.0075 | 0.0760 -> 0.0765 |
| `(mapcar #'1+ ...)` -- untouched, the control | 0.0480 -> 0.0480 | 0.0235 -> 0.0230 | 0.0135 -> 0.0135 |

The control row is identical to three digits on all three backends, which is what
says the rest of this table is the change and not the harness. **The only real
cost is +20% for `map` over a vector in the interpreter** -- the same band the
prelude cursor's declined path cost -- with the JVM flat and WASM within 2%. The
interpreter's `replace` rows do not move at all: its cursor is a NULL CHECK for a
non-list source, not a `consp` per element, which is the one place a Java cursor
beats the Lisp one. (Absolute values differ between the two harnesses -- a
per-`defun` body JITs differently -- so read each table against itself.)

**Bytes.** wasm-GC, marginal cost of one more site, `--optimize=size`:

| site | before | after | |
| --- | ---: | ---: | ---: |
| `(make-array 3 :initial-contents c)` | 1,134 | 1,207 | +73 |
| `(make-array '(2 2) :initial-contents c)` | 2,548 | 2,952 | +404 (two levels) |
| `(map 'list #'1+ c)` | 591 | 664 | +73 |
| `(replace d c)` | 43 | 43 | 0 -- a shared callee |

`replace`'s cursor is paid ONCE, inside `%REPLACE-RUNTIME`: +248 B on the module
and nothing per site. Whole artifacts: `size-report hello_world` and `pi_approx`
are **byte-identical** at every optimize level (no site survives their shake),
and `zlib` grows **365 B** -- +0.29% at `--optimize`, +0.36% at
`--optimize=size` -- for a quadratic class removed from four lowerings.

**Nothing shipped passes a long list to any of the five**, the same finding as
the prelude cursor: this was a latent complexity bug in every case, and the
ladders are what a consumer that does pass one would have paid.

### Proving the answers did not move

16,414 comparisons, zero divergence: the same generated program run on the jar
built from the parent commit and the jar built from this one, output diffed byte
for byte per backend. 3,697 cases x 4 backends (a deterministic LCG over
list/general-vector/packed-vector/string operands, bounds drawn in range, at the
edge, past the end, negative, nil and absent, plus hand-picked dotted lists,
non-sequences, empty sequences, self-aliased `replace`, short and absent nested
rows) and 813 cases x 2 backends for the shapes that TRAP on WASM and so can only
be compared on the interpreter and the JVM -- a rank-1 `:initial-contents` longer
than the array (a write past its end) and a dotted source read past its tail
(`car` of a non-cons). Both trap identically before and after; they are
pre-existing and not this change's to move.

## The last three sites, and where the family ends

Measured 2026-08-31, hours after the five above. `.todo/595` carried what that
survey found and did not fix. **They are three DIFFERENT defects wearing one
symptom**, and only one of them took the cursor:

| the site | the defect | the fix |
| --- | --- | --- |
| the runtime `format` renderer's argument list | `(nth i all)` per directive, `(length items)` TWICE per `~{` pass | MATERIALIZE, not a cursor |
| the `#'map-into` WRAPPER's store | `(setf (elt r i) v)` into a list destination | the cursor, verbatim |
| `(map 'string ...)`'s accumulator | `%string-concat` of the whole result per element -- quadratic in the OUTPUT | a pairwise JOIN |

**The renderer is the one place a cursor was the wrong answer, and saying so is
the finding.** `~*` moves the argument pointer forward, `~:*` backward, `~n@*`
absolutely, and `~?` / `~{` recurse with their own -- the access pattern is
genuinely RANDOM, so a monotone cons cursor needs a re-seed whose cost is the
walk it was meant to remove. `all` is now the pair `%fmt-args` builds -- the list
as given plus a vector of it, materialized once per rendering LEVEL and only for
a PROPER list long enough to pay for it -- read through `%fmt-arg` / `%fmt-count`,
with the same discipline the cursor sites follow: **the fallback is the very
`(nth i list)` / `(length x)` the renderer used to make**, so a negative index, an
index past the end, a dotted list and a non-sequence all answer exactly what they
did. Full mechanics, the cost table and the re-evaluation trigger:
`.kb/format.md`, "The argument list is a materialized VECTOR". The two iteration
loops additionally collect their pieces and join them once, because the
`(%fmt-cat acc piece)` per pass is quadratic in the OUTPUT even after the reads
are O(1) -- the same defect as `map`'s `'string` accumulator, one layer up.

### The ladders

Apple M4 Max, one locked acquisition per table, before and after in the same run,
each row its own `defun`, two rounds and a third at 8x the iterations. **ms per
call.**

`(format nil "~{~a~}" <n-element list>)`:

| n | 250 | 500 | 1000 | 2000 | 4000 |
| --- | ---: | ---: | ---: | ---: | ---: |
| JVM `.class` before | 0.285 | 0.880 | 3.47 | 12.62 | **54.8** |
| JVM `.class` **after** | 0.028 | 0.055 | 0.071 | 0.145 | **0.306** |
| WASM p1 before | 0.340 | 1.115 | 5.53 | 49.3 | **169.3** |
| WASM p1 **after** | 0.137 | 0.269 | 0.544 | 1.103 | **2.231** |
| `--component` before / after | 0.343/0.136 | 1.125/0.270 | 5.61/0.545 | 49.3/1.103 | 169.8/**2.225** |
| interpreter before | 5.76 | 12.08 | 26.6 | 64.4 | **164.9** |
| interpreter **after** | 5.86 | 11.68 | 23.4 | 46.6 | **93.2** |

**179x (JVM) and 76x (wasm-GC) at n = 4000**, and every after-row DOUBLES where
every before-row quadrupled. `~:{` over 2,000 sublists: 14.06 -> **0.40** (JVM),
59.6 -> **2.38** (wasm p1), 113.1 -> **91.5** (interpreter).

`(funcall #'map-into <n-element list> #'1+ <n-element list>)` -- the wrapper takes
the `mapIntoDispatch` cursor verbatim:

| n | 500 | 1000 | 2000 | 4000 |
| --- | ---: | ---: | ---: | ---: |
| interpreter before | 0.770 | 2.020 | 5.35 | **18.13** |
| interpreter **after** | 0.493 | 0.978 | 1.948 | **3.894** |
| JVM before | 0.195 | 0.700 | 2.87 | **11.40** |
| JVM **after** | 0.0037 | 0.0075 | 0.0163 | **0.0312** |
| WASM p1 before | 0.153 | 0.560 | 2.69 | **21.85** |
| WASM p1 **after** | 0.021 | 0.045 | 0.093 | **0.181** |

**365x (JVM) and 121x (wasm-GC) at n = 4000.** The wrapper was 47-54x slower than
the same call in CALL position; it is now within 2.5x of it.

`(map 'string ...)` and the literal `(coerce x 'string)` that shares its body.
**The source representation decides what this row measures, and `.todo/595`'s
number was measuring the other thing** -- see below:

| n = 1000 / 2000 / 4000 | interpreter | JVM | WASM p1 |
| --- | --- | --- | --- |
| over an ORDINARY string, before | 1.68 / 3.72 / 9.80 | 0.19 / 0.58 / 0.85 | 0.83 / 3.06 / **11.95** |
| over an ORDINARY string, **after** | 1.54 / 3.04 / **6.01** | 0.053 / 0.045 / 0.206 | 0.114 / 0.235 / **0.475** |
| over a LIST of characters, before | 1.40 / 3.58 / 10.15 | 0.20 / 0.46 / 0.90 | 0.81 / 3.02 / **12.00** |
| over a LIST of characters, **after** | 1.73 / 3.42 / **6.93** | 0.054 / 0.098 / 0.094 | 0.111 / 0.223 / **0.469** |
| `(coerce <n chars> 'string)` before | 0.01 / 0.02 / 0.05 | 0.14 / 0.34 / 0.35 | 0.82 / 3.02 / **11.95** |
| `(coerce <n chars> 'string)` **after** | 0.01 / 0.02 / 0.05 | 0.026 / 0.050 / 0.094 | 0.103 / 0.210 / **0.431** |

**25-28x on wasm-GC at n = 4000**, and the after-rows double where the before-rows
quadrupled. The interpreter's `coerce` never ran this body (it has the native
arm), and the JVM was never badly hurt -- `String.concat` of a 4,000-character
string is a memcpy the JIT is good at, which is why its before-row is not a clean
quadratic.

**A `.kb`/todo premise this corrects.** `.todo/595` measured
`(map 'string #'char-upcase <4000-char string>)` at 56.2 ms on wasm-GC and
attributed it to the accumulator. The source there was a `make-string`, which on
both compiled backends is a mutable character VECTOR whose `(char v i)` renders
the whole vector per access (`.kb/adjustable-arrays.md`, `.kb/geom.md`) -- that
row is quadratic in the READ, a separate and already-owned defect (`.todo/343`),
and it barely moves: 55.1 -> **45.9** on wasm p1, 22.15 -> **20.63** on the JVM,
9.75 -> **6.44** in the interpreter (whose character vector IS a `LispString`, so
it has no render to pay). Over an ORDINARY string -- what every allocated string
is -- the accumulator is what the row measures, and it is the table above.

### What it costs

Each row in its own `defun`, ms per call, before -> after. The last two rows are
CONTROLS this change does not touch:

| row | interpreter | JVM | WASM p1 |
| --- | --- | --- | --- |
| `(format nil "~a ~a" 1 2)`, computed control | 0.0398 -> 0.0425 (+7%) | 0.0003 -> 0.0003 | 0.0008 -> 0.0010 |
| the same with 8 arguments (at the threshold) | 0.1435 -> 0.1545 (+8%) | 0.0005 -> 0.0006 | 0.0030 -> 0.0033 |
| `(format nil "~{~a~}" <8-element list>)` | 0.221 -> 0.243 (+10%) | 0.0010 -> 0.0009 | 0.0050 -> 0.0052 |
| `#'map-into` into an ARRAY, n = 4000 | 5.10 -> 6.38 (+25%) | 0.275 -> 0.028 | 0.225 -> 0.231 |
| `(map-into ...)` in CALL position, n = 4000 | 3.95 -> 3.86 | 0.100 -> 0.100 | 0.0750 -> 0.0750 |
| `(map 'list #'char-upcase <4000-char string>)` | 1.85 -> 1.91 | 0.100 -> 0.106 | 0.100 -> 0.094 |
| `(mapcar #'1+ ...)`, n = 4000 -- the control | 0.050 -> 0.050 | 0.025 -> 0.025 | 0.025 -> 0.025 |

7-10% for a short `format` on the interpreter (the extra walk, one cons and a
`%fmt-arg` call per read, for a vector it never builds) and +25% for an ARRAY
destination in the `#'map-into` wrapper (one `consp` per element for a cursor it
never uses) -- the same band the earlier declined paths cost. The two control
rows are identical to three digits on all three backends, which is what says the
rest of the table is the change and not the harness.

**Bytes.** wasm-GC at `--optimize=size`, and the `.class` beside it:

| program | wasm | `.class` |
| --- | ---: | ---: |
| `(print 1)` | 496 -> 496 | 3,008 -> 3,008 |
| one computed-control `format` | 75,535 -> 78,111 (+2,576) | 72,398 -> 75,678 |
| the same with a `~{` | 75,586 -> 78,162 (+2,576) | 72,465 -> 75,745 |
| one `(map 'string ...)` | 20,344 -> 20,894 (+550) | 8,977 -> 9,810 |
| one `(coerce x 'string)` | 12,530 -> 13,080 (+550) | 13,039 -> 13,874 |
| one `#'map-into` as a value | 23,932 -> 24,020 (+88) | 38,916 -> 39,080 |
| `size-report hello_world` / `pi_approx` | byte-IDENTICAL | -- |
| `size-report zlib` | 101,615 -> 102,200 (+585, +0.58%) | -- |

The renderer's +2,576 B is paid only by a program that reaches the runtime
renderer at all, which the gate already keeps to the programs that need it
(`.kb/format.md`); a program that formats only literals carries none of it, which
is what the two byte-identical size-report rows say.

### Proving the answers did not move

**11,248 comparisons, zero divergence**: one generated program run on the jar
built from the parent commit and the jar built from this one, output diffed byte
for byte per backend. 2,510 cases x 4 backends -- a deterministic LCG over 79
control strings x 35 argument sets, with the full cross product of every
iteration and repositioning directive, argument lists of 0/1/2/3/7/8/9/12/20/40
elements (straddling the materialization threshold both ways), sublists,
characters, floats, nils, non-sequences, plus every `#'map-into` destination x
source representation (list, long list, empty, array, fill-pointered array,
string, 0/1/2 sources) and every `(map 'string ...)` / `(coerce x 'string)` /
`concatenate` / `reverse` / `remove` / `substitute` / `remove-duplicates` /
`sort` shape over string, list, vector and empty operands -- and 604 cases x 2
backends for the shapes that trap uncatchably on wasm before this change and
after it (a string or a vector where a directive indexes a sequence, a dotted
argument list, a sublist of non-lists, a type error inside `map-into`, `~/name/`
against the stub).

### Is the family closed? Yes -- here is the search

`(elt <seq> <i>)` / `(nth <i> <seq>)` with a RUNTIME-sized sequence and a loop
index, everywhere one can be written:

- **the shipped Lisp** (`src/main/resources/**/*.lisp`) -- every remaining hit is
  a constant index into a record (`http-server.lisp`, `asdf.lisp`, `geom.lisp`'s
  glTF accessors), an axis into `array-dimensions` (`linalg.lisp`, `torch.lisp` --
  bounded by RANK), or `usocket.lisp`'s four-octet address. The one true instance
  is `geom.lisp:422-423`'s `(nth i facet)`, bounded by a polygon's vertex count
  (3-4 in every mesh the repo builds) and dismissed with a measurement in
  `.todo/594`'s survey.
- **the Java-embedded prelude** (`LispPreludeLibrary.SOURCES`) -- the only `elt`
  forms left are the four cursor FALLBACKS `search` / `mismatch` / `replace` were
  given above.
- **`LispMacroExpander`'s lowerings** -- `expandElt` and `array-dimension` are
  single reads, not loops; `arityDispatchedCall`, `bindParams` and
  `WasmArityBundler` are bounded by a lambda list's length; every generated loop
  over a sequence now carries `readElementAdvancing` / `cursorRead` or the
  `readElement` / `advanceCursor` pair.
- **native `Environment` / `LispEvaluator` arms** -- `sequenceRef` has exactly one
  caller inside a loop, `SequenceSourceCursor`, which is the cursor; every other
  per-element read is `elementAt` on a packed vector (O(1)) or walks a
  `seqAsList` materialization.
- **`BuiltinFunctionWrappers`** -- the one `(setf (elt r i) v)` left is the
  `#'map-into` wrapper's ARRAY arm, where the store is O(1) by construction.

The one shape that is still super-linear and is NOT this family: `%fmt-run`'s own
`(%fmt-cat out (string (char ctrl pos)))` per character, quadratic in the CONTROL
STRING's length. Irrelevant at the length control strings have, and it is why the
iteration loops -- whose accumulator grows with the DATA -- were the ones changed.

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
  the defun. The cursor rewrite is the worked example: it changed no answer at
  all (an `elt` fallback wherever the cursor cannot reach), so the arm needed no
  edit -- 8,042 randomised comparisons of the old body against the new, on all
  four backends, with every representation, every bounding keyword, out-of-range
  and negative bounds, dotted lists and a non-sequence.
- **An out-of-range bound is the prelude's to answer, and a `(null cell)` cursor
  stop would take that away.** `replace`'s list-source arm took one and changed
  what an invalid call answers (`.kb/sequence-op-runtimes.md`); here the arm
  declines those bounds specifically so this body owns them, so the cursor falls
  back to `elt` instead of stopping. If the family ever agrees on a uniform
  error for an out-of-range bound, the honest fix is a check at the top of both
  bodies -- and the arm's bound declines can be lifted in the same commit.
- **The declined shapes cost 126-171 us**, up 12-15% from the `elt`-indexed body:
  the cursor's `consp` test per element is paid by a string operand that never
  uses it. `:key`, a user `:test` and an out-of-range bound run the prelude at
  that price. Serving them means calling back into `apply` per element, which is
  a different trade (~0.4 us a pair rather than 2.5, not 0.01) and a much wider
  agreement surface; measure a real consumer before taking it.
- **`mismatch` ignores `:from-end` on ALL FOUR backends**, documented as a "Lite"
  deviation. If it is ever made CLHS-correct, that is a prelude-SOURCE change --
  it moves every backend at once -- and the arm's decline can then be lifted in
  the same commit.
- **A new loop over a sequence whose representation is a RUNTIME fact must carry
  a cursor from the start.** Five sites had the defect and none of them was in
  the prelude; the shape is a generated `do`/`dotimes` whose body reads
  `(elt s i)` or `(nth i s)`, and the fix is `readElementAdvancing` (a plain
  `elt` fallback) or `cursorRead` (any other fallback). `LispMacroExpander`'s
  `readElement`/`advanceCursor` pair is the OTHER spelling, for a loop that has a
  `do` binding to hang the step on; `map-into`'s dispatch is its only user.
- **The family IS closed, as of 2026-08-31** -- the three sites `.todo/595`
  carried are the section above, and the search that says nothing is left is
  recorded with them. What can reopen it is a NEW loop, not an old one: any
  generated `do`/`dotimes`/`while` whose body reads `(elt s i)` or `(nth i s)`
  where `s` can be a list at run time. The fix is `readElementAdvancing` (a plain
  `elt` fallback), `cursorRead` (any other fallback), the `readElement` /
  `advanceCursor` pair (a loop with a `do` binding to hang a step on) -- or, when
  the index does NOT advance monotonically, `%fmt-args`'s materialize-once shape,
  which the renderer is the worked example of.
- **A quadratic ACCUMULATOR is the family's twin and the cursor cannot see it.**
  `(map 'string ...)` and the renderer's `~{` loop each rebuilt their whole result
  once per element while their reads were being fixed beside them. Any loop whose
  accumulator step is `%string-concat`/`concatenate` of the accumulator itself is
  O(n^2) in the OUTPUT; collect and join (`joinStringPiecesReversed`, `%fmt-join`)
  rather than fold. `.kb/map-family.md` has the worked example.

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
  :from-end`) in one program, plus the list-cursor rows the rewrite below added:
  a list in either operand, a bound past the end and a negative one, a dotted
  list, and a 400-element haystack.
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
- `LispEvaluatorTest.searchAndMismatchWalkAListWithACursorRatherThanIndexingItWithElt`,
  `JvmLispCompilerTest.compileSearchAndMismatchWalkAListWithACursor` and
  `WasmLispCompilerIntegrationTest.searchAndMismatchWalkAListWithACursor` -- the
  cursor's own surface: a list in either operand and both, the needle window, a
  bound past the end and a negative one, a dotted list, and a 400-element list
  whose answers the quadratic body gave just as slowly. The interpreter one goes
  through `(funcall #'search ...)`, which is the `defun` and never the arm.
- The rest of the family (the section above), all four backends:
  `ci-spec.yaml`'s `make-array-rank2-initial-contents` (extended with run-time
  list, vector, string and packed contents at rank 1, 2 and 3, and both
  mixed-representation nestings), `replace-into-a-list` (extended with a LIST
  source into a list destination, bounded and past the end) and `map` (extended
  with list, vector and string operands, both arities and every result type);
  `LispEvaluatorTest.replaceReadsAListSourceThroughACursorRatherThanIndexingItFromTheHead`
  -- including the three `sequence-ref: index N out of range` signals a silent
  cursor stop would have erased, and a self-aliased `replace`;
  `JvmLispCompilerTest.compileAndRunMakeArrayInitialContentsWalksAListWithACursor`
  / `#compileAndRunMapWalksAListWithACursor` /
  `#compileAndRunAListIntoAListReplaceReadsItsSourceWithACursor` and the WASM
  twins `makeArrayInitialContentsWalksAListWithACursor` /
  `mapWalksAListWithACursor` / the list-source rows appended to
  `replaceIntoAList`. Each includes a 2,000-element operand -- the length at
  which the head-walk was already seconds of test time.
- The last three (the section above), all four backends:
  `ci-spec.yaml`'s `format-runtime-control-string` (extended with the four
  repositioning directives on both sides of the materialization threshold,
  `~#`, the four iteration shapes, a 3,000-element `~{`, and a non-list `~{`
  argument), `map-into` (extended with the `#'map-into` WRAPPER over every
  destination representation, including two 20,000-element lists) and `map`
  (extended with the `'string` result over odd, even, empty, one-element and
  multi-character-piece sequences, and a 5,000-character one);
  `LispEvaluatorTest.theFormatRendererReadsItsArgumentListThroughAMaterializedVectorRatherThanNthPerDirective`
  / `#theMapIntoWrapperWalksAListDestinationWithACursorRatherThanStoringByIndex`
  / `#theStringResultOfMapCollectsItsPiecesAndJoinsThemOnceRatherThanConcatenatingPerElement`
  -- including the signal a `~{` over a string still raises, which the
  materialization would have erased had it not kept `nth` as its fallback -- and
  the `JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest` twins
  `compileAndRunTheFormatRendererReadsItsArgumentListThroughAMaterializedVector` /
  `#compileAndRunTheMapIntoWrapperWalksAListDestinationWithACursor` /
  `#compileAndRunTheStringResultOfMapJoinsItsPiecesOnce` (the WASM ones without
  the `compileAndRun` prefix).
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
