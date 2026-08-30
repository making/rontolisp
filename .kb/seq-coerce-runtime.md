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
