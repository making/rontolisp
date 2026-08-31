# The string arm of an indexed write is ONE function, not an inlined rebuild

**Invariant: no `(setf (aref v i) x)` / `(setf (elt v i) x)` / `(setf (char s i) c)`
site emits the string rebuild inline. Every one of them calls
`%schar-set-runtime`, which the program carries at most once.**

## The lowering

`LispMacroExpander.expandSetf` gives a rank-1 indexed place a runtime string arm
(`.kb/adjustable-arrays.md` for why: a rank-1 array place may hold a string, and CL
says `(setf (aref s i) c)` on one is legal). Four place heads reach it -- `aref` /
`svref`, `elt`, `char` / `schar`, and `row-major-aref` -- and all four funnel into
`%schar-set`, which the compile paths expand with `expandScharSetFunctional`:

```lisp
(let ((__schar_i i))
  (let ((__schar_c c))
    (setq v (%schar-set-runtime v __schar_i __schar_c))
    __schar_c))
```

`scharSetRuntimeDefun()` is the callee, and it answers **the string the write leaves
behind** -- the same object for a mutable character vector (written in place through
`%row-major-aset`), a fresh one for an immutable string (rebuilt around the replaced
character). That one answer is what lets a single call-site shape serve both arms; the
`setq` back into the variable is why the place must be a VARIABLE, the lite semantics
`.kb/adjustable-arrays.md` already documents.

**The rebuild uses `%subseq-core`, not `subseq`.** It runs only where `%arrayp` said
no, so the general-array copy arm that `expandSubseqCompat` wraps every plain `subseq`
in -- a `%array-alike` plus an inline `dotimes` copy loop -- is dead there. Skipping it
is the difference between a **7,187**-byte helper and a **665**-byte one.

**Injection**, `withScharSetRuntime` in `expandTopLevelDefinitions`, at both of its
exits (the same two `withFormatRenderer` uses, `.kb/format.md`). It has to be a scan of
the pre-expansion program -- expression expansion happens per form much later and
cannot add a top-level defun -- so it names the PLACE HEADS (`aref`/`svref`/`elt`/
`char`/`schar`/`row-major-aref`/`%schar-set`, anywhere in the form, in any position)
rather than trying
to predict which of them will keep the string arm. **Deliberately generous**:
over-injecting costs one unreachable defun, which `--optimize` drops and which is
byte-identical without it in every program measured; under-injecting would be a call to
a function that does not exist. The interpreter never sees any of this -- it does not
run `expandTopLevelDefinitions`, and its `%schar-set` is a real in-place primitive.

## A string LITERAL is never written, on any backend

**Invariant: `(setf (char s i) c)` / `(setf (schar s i) c)` / `(setf (aref s i) c)` /
`(setf (elt s i) c)` where `s` holds a string LITERAL rebuilds the string and rebinds
the place on ALL FOUR backends; the source constant is untouched and
`(eq (f) (f))` on a literal stays `T`.** Where the place cannot be rebound the write is
an ERROR on all four -- refused at compile time by the three compile paths, at run time
by the interpreter. Pinned by the `string-literal-write-cross-backend` ci-spec case.

The reader marks its own `LispString`s (`LispString.literal`, `LispReader`'s
`StringToken` arm; `LispString.sourceLiteral()` reads the mark), because that object IS
the program text: it answers every evaluation of the form it appears in, for the life of
the program. Nothing else is marked -- a `copy-seq`, `concatenate`, `subseq` or `format`
result is an ordinary allocated string, and the rebuilt string a literal write answers is
ordinary too.

The interpreter's half is `LispEvaluator.evalScharSet` (a `%SCHAR-SET` arm in
`evalCons`, not a plain builtin call, because the callee cannot rebind its caller's
variable): it evaluates the three subforms once, then hands
`Environment.scharSet` a rebind hook when -- and only when -- the place subform is a
SYMBOL. `Environment.scharSet` owns the whole rule: bounds first, then the literal
branch (rebuild via `LispString.withCharAt` through the hook, or throw when there is no
hook), then the in-place write every other string still gets. `%schar-set` as a
first-class function value has no hook, so it refuses a literal too.

### Measured 2026-08-29, all four backends, before and after

| program | before | after |
|---|---|---|
| `(eq (fs) (fs))` for `(defun fs () "abc")` | `T` on all four | unchanged, `T` on all four |
| `(let ((a (fs))) (setf (char a 0) #\Z) a)` | `"Zbc"` on all four | unchanged |
| the next `(fs)` | interp `"Zbc"`, compiled `"abc"` | `"abc"` on all four |
| `(setf (char "abc" 0) #\Z)` | interp silently mutates, compiled = compile error | error on all four |
| `(setf (char (aref v 0) 0) #\Z)`, `v` a `#("abc")` | interp corrupts, compiled = compile error | error on all four |
| a literal passed to `(defun f (s) (setf (char s 0) #\Z))` | interp corrupts the constant | rebinds the parameter only, on all four |

**The limits come with the rule and are not silent.** The place must be a VARIABLE (the
rebuilt string has nowhere else to go), and the update is invisible through an alias
taken before the write -- `.kb/asdf.md`, cl-base64 item 3.

### The BULK writes, settled 2026-08-30

**Invariant: a DESTRUCTIVE BULK operation whose target is a string literal --
`replace`, `fill`, and the `(setf (subseq s start end) v)` that lowers to `replace` --
lands on a FRESH COPY on all four backends. The modified string reaches the program
only as the operation's RETURN VALUE; the variable is not rebound and the source
constant is untouched.** Pinned by the `string-literal-bulk-write-cross-backend`
ci-spec case and
`LispEvaluatorTest.aBulkWriteThroughAStringLiteralLandsOnACopyAndLeavesTheConstant` /
`#aBulkWriteThroughAnAllocatedStringBufferIsStillInPlace`.

**Why the copy and not the rebind `%schar-set` performs.** `replace` and `fill` are
FUNCTION calls, not place forms: there is no place to rebind, and `(setf (subseq ...))`
hoists its sequence subform into a temporary before reaching `replace`, so it has none
either. The return value is the only channel, and it is already the channel all three
compile paths use -- their functional branch builds the new string and hands it back.
So the answer costs nothing on the compile side: the INTERPRETER moved, alone, and
`Environment`'s `replace` / `fill` string arms copy through `LispString.copyForBulkWrite`
when `sourceLiteral()` says the target is program text.

Measured 2026-08-30, `(defun r () "abc")`, each row run as
`(let ((a (r))) <form> a)` and then a fresh `(r)`. The compile-path column never moved:

| form | interpreter before | interpreter after | JVM / WASM P1 / WASM component |
|---|---|---|---|
| `(replace a "Z")` | `"Zbc"`, next `(r)` `"Zbc"` | returns `"Zbc"`, `a` and next `(r)` `"abc"` | same as after |
| `(fill a #\Q)` | `"QQQ"`, next `(r)` `"QQQ"` | returns `"QQQ"`, both `"abc"` | same |
| `(setf (subseq a 0 1) "Y")` | `"Ybc"`, next `(r)` `"Ybc"` | returns `"Y"`, both `"abc"` | same |
| `(nstring-upcase a)` | already correct | unchanged | same |
| `(nreverse a)` / `(nsubstitute ...)` / `(map-into a ...)` / `(sort a ...)` | already correct | unchanged | same |

So only three of the family ever diverged: the two that reach `LispString`'s in-place
`replaceInPlace` / `setCharAt`, plus the `setf` that lowers to one of them. The `n*`
operators and `map-into` were already functional on the interpreter, which is why the
2026-08-29 table in `.todo/581` -- taken before `.todo/580` landed -- listed
`nstring-upcase` as diverging and this one does not.

`%aset` / `%row-major-aset` called DIRECTLY (not through a `setf` place -- no rebind
hook reaches them that way) REFUSE a literal rather than copy
(`LispEvaluatorTest.rowMajorAsetOnAStringLiteralAsAFirstClassCallIsStillAnError`):
they are indexed writes with no rebind hook, exactly `%schar-set`'s first-class-value
case. **CLOSED 2026-08-30 (`.todo/587`): `row-major-aref` is now on the routed list
too**, so `(setf (row-major-aref s i) c)` never reaches `%row-major-aset` for a
VARIABLE place -- it is the same string arm `aref`/`svref`/`elt` have, and gives the
same three answers: in place for a mutable character vector, a rebind that leaves a
literal untouched, an error for a place `expandSetf` cannot rebind (a non-variable
place still falls through to `%row-major-aset`, which still cannot take a string --
the `(setf (aref s i) c)` spelling has the identical restriction).

`row-major-aref`'s READ arm was also missing on two backends, independent of the write
question: the interpreter's `ROW-MAJOR-AREF` had no `LispString` case
(`Environment`, throwing `ROW-MAJOR-AREF expects an array`) and both wasm-GC backends'
`compileRowMajorAref` dispatched only the farray/packed-int-vector/general
representations and trapped (`cast failure`) rather than test for a string; the JVM's
`compileRowMajorAref` already shared `aref`'s rank-1 helper and needed no change. Fixed
by giving the interpreter the same `charRef` call `AREF` makes, and by having
`compileRowMajorAref` call the same `emitAref1FromSlots` slot dispatch `compileAref`'s
rank-1 fallback uses (which already had the string arm) instead of repeating the
three-way chain without it.

### What this does NOT cover, measured the same day

The line is drawn at the SOURCE CONSTANT, not at immutability, and the two do not
coincide:

- ~~**An allocated immutable string still diverges on an alias.**~~ **CLOSED 2026-08-31
  (`.todo/559` step 2): a `copy-seq`/`subseq` result IS a mutable character vector on
  the compile paths now** (see "A copy-seq/subseq result is mutable with identity"
  below), so `(let* ((s (copy-seq "abc")) (b s)) (setf (char s 0) #\Z) (list s b))`
  answers `("Zbc" "Zbc")` on all four backends, and a callee's write to a string
  argument reaches its caller. A literal's sharing is made by the READER and a literal
  stays immutable, so the two rules still do not collide.
- ~~**An allocated immutable string still loses a BULK write on the compile paths.**~~
  **CLOSED the same day, by the same flip:** `%arrayp` is true for the character vector
  a `copy-seq` now answers, so `expandReplace`/`expandFill` take their DESTRUCTIVE
  branch and `(let ((s (copy-seq "abc"))) (replace s "Z") s)` answers `"Zbc"` on all
  four. No functional-branch surgery was needed -- exactly as `.todo/559` predicted.
- **A `#P"..."` / `#S(...)` literal is `eq` to itself only on the interpreter.** CLOSED
  2026-08-30 by `.todo/581`: both compile backends now memoize a bare instance literal
  into the same lazy slot a quoted datum uses, so `(eq (fp) (fp))` for
  `(defun fp () #P"a/b.txt")` is `T` on all four. `.kb/quoted-data.md` carries the
  mechanics and the cost.
- A string nested inside an array literal is SHARED on all four (`#("abc")`:
  `(eq (aref (f) 0) (aref (f) 0))` is `T` everywhere) even though the array around it is
  fresh per evaluation, because `LiteralArrays.materialize` passes a non-array element
  through by identity. Writing through it obeys the rule above.

## A copy-seq/subseq result is mutable with identity (2026-08-31, `.todo/559` step 2)

**Invariant: a string the program allocates through `subseq` / `copy-seq` is a MUTABLE
sequence with identity on ALL FOUR backends, matching SBCL: aliases see each other's
writes, a callee's write to a string argument reaches its caller, `replace`/`fill`
write in place, and a displaced view over such a string writes THROUGH to it. A
LITERAL stays immutable and the rules above are unchanged.** Pinned by the
`string-identity-cross-backend` ci-spec case,
`JvmLispCompilerTest.compileDisplacedStringViewOverACopySeqResultWritesThrough` /
`WasmLispCompilerIntegrationTest.compileDisplacedStringViewOverACopySeqResultWritesThrough`
(rewritten from the promote-on-write tests `.todo/559` planted to fail when this
landed), and the `nstring-upcase (copy-seq ...)` rows of the enquiry/namestring tests.

The mechanism is the PRODUCER: the string lane of `%subseq-core` answers a fresh
mutable CHARACTER VECTOR instead of an immutable value. `copy-seq` is
`(subseq seq 0)` everywhere, so one lane covers both.

- **JVM**: `JvmSubseqCompiler`'s string arm (under the array gate) is one call to
  `_subseqCv(Object, int, int)` (`JvmArrayRuntimeBuilder`): a character-vector or
  string-view input copies elements `[start, end)` through `_rmGet` -- never rendering
  the source, so chained slicing stays linear -- and an immutable `String` slices by
  code point and converts once through `_strToCharVec` (fill-pointer slot cleared: a
  subseq result is a SIMPLE string). `subseq`/`copy-seq`/`replace` therefore joined
  `programUsesAnyArrayOp`, and the class shaker keeps the growth modest: a
  subseq-only program grew 3,908 -> 13,419 bytes, not the full ~120 KB array runtime.
- **WASM**: the lane calls `_subseq_str` (`FUNC_SUBSEQ_STR`,
  `WasmStringRuntimeBuilder.buildSubseqStrBody`): `_charvec_p` -> element copy through
  `_arr_get`, else the byte-level `_subseq` whose string result converts once through
  `_str_to_cv` (`FUNC_STR_TO_CV`); a cons-chain result passes through. +272 bytes on a
  subseq-carrying module, byte cost zero where the shake drops the helpers.
- **Interpreter**: unchanged -- its strings were always mutable.

**The boundary chokepoints render a character vector exactly once.** A charvec now
flows everywhere a string can, so the places that read a string's BYTES normalize at
entry instead of trusting the representation: WASM `_str_to_mem` (every host/linear
crossing), `_write_line` / `_write_stream_str` (returning the ORIGINAL argument, so
identity survives the write), the component import string lowering
(`emitStageStringParam` -- the fetch URL parts the library slices out), and the
`%str-byte-length` / `%str-byte-ref` socket accessors; JVM `JvmIoRuntimeBuilder`
mints `_strv` (only under the array gate) and calls it before every
`(String)` path/content cast (`emitStripQuotes`, `_open`, `_probeFile`,
`_listDirectory`, `_writeLine`, the socket write, `_makeStringInputStream`).

**What it costs, measured 2026-08-31 (corpus-shaped rows, step 1 -> step 2, ms):**
JVM json-parse 23 -> 28, json-stringify 22 -> 30, format-nil flat (32 -> 30),
tokenizer (position + subseq + string=) flat (229 -> 221), string-upcase 17 -> 19,
json-parse of a subseq-fed source 6 -> 13; WASM p1 json-parse 10 -> 12, stringify
20 -> 24, format flat, tokenizer flat, subseq-fed parse 9 -> 17. The interpreter rows
are the unmoved control. The whole-string consumers (`string=`, concatenate, intern,
hash, print) render a charvec once per CALL -- `.todo/343`'s remaining scope -- which
is where those percentages live; nothing is quadratic, because the index sites read
elements (`string-index-cost.md`) and `subseq`-of-charvec copies the slice, not a
render of the source.

~~**The residue is the OTHER producers**: `concatenate` / `string-upcase` / `format nil`
/ `princ-to-string` / `read-line` / getenv / fetch results still answer immutable
strings.~~ **CLOSED for the main producers 2026-08-31 (`.todo/596`)** -- see "The
remaining producers are flipped" below; what is STILL immutable is named there.


## The remaining producers are flipped (2026-08-31, `.todo/596`)

**Invariant: a string the program allocates through `concatenate 'string`, the case
family (`string-upcase` / `string-downcase` / `string-capitalize`), a literal-`nil`
destination `format`, the string-stream capture (`with-output-to-string` /
`get-output-stream-string`) or `read-line` is a MUTABLE sequence with identity on ALL
FOUR backends, exactly like the copy-seq/subseq result above. A LITERAL stays immutable
and the literal rules are unchanged.** Pinned by the extended
`string-identity-cross-backend` ci-spec case,
`LispEvaluatorTest.aFlippedStringProducerResultHasWritableIdentity`,
`JvmLispCompilerTest.compileAFlippedStringProducerResultHasWritableIdentity` /
`#compileALiteralArgumentProducerCallIsNotFoldedIntoASharedLiteral` and their
`WasmLispCompilerIntegrationTest` twins.

The mechanism is a per-SITE wrap, not a per-primitive rewrite: each flipped case in
`Jvm`/`WasmExprCompiler` compiles the producer as before and finishes with ONE call --
`_toMutStr(Object)` on the JVM (`JvmArrayRuntimeBuilder`), `_to_mut_str`
(`FUNC_TO_MUT_STR`, `WasmStringRuntimeBuilder.buildToMutStrBody`) on WASM. The wrap
converts a QUOTE-FRAMED string once through `_strToCharVec` / `_str_to_cv` (fill
pointer cleared -- a producer result is a SIMPLE string) and passes everything else
through. **The frame test is load-bearing**: a SYMBOL shares the string representation
BARE on both backends (a `java.lang.String` without quotes; a `TYPE_STRING` whose
byte 0 is not 0x22), and `read-line`'s eof-value or a symbol flowing out of a wrapped
expression must not be laundered -- unframed, the conversion would even trip the UTF-8
decode. `%string-concat` stays UNWRAPPED: it is the internal append the codegen's own
expansions (format pieces, the renderer) build with, so only the value the PROGRAM
receives pays the conversion.

**Both backends wrap under ONE gate**, `compiler/MutableStringProducers.programUsesAny`
-- a pre-expansion source scan for the producer names (plus the `(format nil ...)`
shape and the `%read-line-raw` component alias), computed identically by
`JvmLispCompiler` and `WasmLispCompiler` (and copied into async chunk contexts by
`WasmAsyncEmit`), so the backends cannot disagree about which results carry identity.
On the JVM the scan also joined `programUsesAnyArrayOp`, so a wrap site always has the
array runtime -- same reasoning as subseq's line.

**The fold folds to a per-evaluation copy**: `PureBuiltinFolder` used to reduce a
literal-argument `string-upcase` / `string-downcase` / `(concatenate 'string ...)` /
`subseq` to one SHARED literal, which forges exactly the aliasing this flip provides
(and had already been forging it for `(subseq "lit" 0)` since the subseq flip). Those
four entries now fold to a `(%str-fresh "...")` constant the backends compile as the
literal plus one mutable-copy wrap — the value is still computed at compile time, the
static-print payoff survives, and each evaluation answers a fresh mutable string.
`symbol-name` / `princ-to-string` / `prin1-to-string` keep the plain literal, because
their runtime producers still answer immutable values. Mechanics, corrected premise
and the three-way size table: `.kb/pure-builtin-fold.md`, "The fresh-string producers
fold to a per-evaluation copy".

**Byte-oriented library accumulators must NOT go through the wrapped `concatenate`.**
sockets.lisp's and stdin.lisp's read-char/read-line/write-sequence accumulators build
strings whose bytes ARE wire bytes -- partial UTF-8 sequences, or raw bytes that are
not UTF-8 at all -- and the wrap's character conversion traps on an invalid lead byte
(found live: a component `write-sequence` of `(vector 1 2 250 4)` hit
"out of bounds array access" in the UTF-8 decode) and would re-encode raw bytes >= 0x80
even when it does not trap. Those sites now append through `%string-concat` directly,
with a comment saying why at each one.

**Boundary seams this flip found and fixed** (each a `(String)` cast the new charvec
density reached): the JVM `%error`/`warn` message casts
(`JvmErrorCompiler.compileThrowRuntimeException`, `JvmWarnCompiler` -- a condition
report lambda's `with-output-to-string` capture is a charvec), the JVM socket write
path (`JvmSocketRuntimeBuilder.emitStripQuotes`, minting `_strv` like
`JvmIoRuntimeBuilder`), the JVM fetch runtime's URL/method/header/body strips
(`JvmFetchRuntimeBuilder`, threaded `strvRef` -- the relay-handler shape builds its
upstream URL with `concatenate`), `load`'s path (`JvmLoadCompiler`), and the HTTP
transport boundary, fixed in Lisp for all backends at once: `%http-header-name` /
`%http-header-value` / `%http-join-strings` render through `(string ...)` before the
triple crosses into `RontoHttpClack.toResponse`, whose `instanceof String` guards
silently DROPPED charvec headers and bodies. json.lisp's `%json-pairs` merge switched
to `%string-concat` (the wrapped merge re-converted every intermediate: JVM
json-stringify 116 -> 245 ms before, 83 ms after -- faster than baseline) and
`%json-stringify` hands its one caller-visible result through `copy-seq`.

**What it costs, measured 2026-08-31 (Apple M4 Max, one locked run, min of two
passes, each row its own defun, the integer control unmoved; baseline -> flipped,
ms):** JVM: json-parse 47 -> 41, json-stringify 135 -> 90 (the `%json-pairs` fix --
the wrapped merge alone had measured 245), tokenizer (`position` + subseq + string=
over a concatenate-built ~4,600-char source) **1,018 -> 29**, subseq-fed json-parse
42 -> 28, format-nil 14 -> 16, upcase of a 1,000-char source x2,000 27 -> 46 (+70%),
wots capture 10 -> 19, read-line loop 9 -> 16, concatenate accumulator (50 appends
x 200) 2 -> 27 (~1.5 us per append). WASM p1: tokenizer **3,221 -> 52**, json-parse
of a concatenate-built source 75 -> 97 (+29%), stringify 109 -> 121, format-nil
9 -> 10, upcase 19 -> 40 (+110%), wots 10 -> 18, read-lines 4 -> 13, fmt-render
5 -> 7, concat accumulator 4 -> 29. The interpreter's tokenizer moved too
(1,501 -> 1,078): the tokenizer rows are the `buildPositionScan` rework landing WITH
this flip -- `position`/`find` used to COERCE the whole sequence to a list per call
(O(n) conses before the first element was read, `.kb/seq-coerce-runtime.md`), which
both set that row's old floor and made the charvec walk look catastrophic on top of
it; the scan now walks a list through its cons cursor and a string/vector by index,
so the row beats the old baseline by ~35x instead of regressing 139%. What remains
above baseline is the wrap-density family -- one render-in and/or one convert-out
per producer call (upcase, the capture, read-line, the accumulator idiom) -- bounded
constant factors in the microsecond range per call, owned by `.todo/343`'s read
lanes (which also records the `(string x)` escape hatch). Sizes: hello_world /
pi_approx byte-identical, zlib +110 bytes (+0.09%); a literal-argument producer
program pays ~0.9 KB of wasm for the fold's per-evaluation copy
(`.kb/pure-builtin-fold.md`, the three-way table).

**What is STILL immutable, and why**: `princ-to-string` / `prin1-to-string` /
`write-to-string` (the static `format` lowering emits its `~a`/`~s` pieces through the
same compiler case, so wrapping it would tax every literal-control format per piece),
a COMPUTED format destination that is nil at run time (only the literal-`nil` spelling
is a producer; the gate would otherwise drag the array runtime into every
`(format stream ...)` program), the first-class `#'format` / `#'concatenate` wrapper
bodies (they build through the renderer / `%string-concat`, not the wrapped cases),
`string-trim` family, `map 'string` / `coerce 'string` / `reverse` / `remove` /
`substitute` results, `symbol-name` and `gensym`/`make-symbol` names (CLHS leaves
symbol-name mutation undefined; deliberate), getenv / fetch / socket-read results,
json-parse's multi-fragment string values (single-fragment ones are subseq slices and
mutable), and a STRING eof-value handed back by `read-line` at EOF is `equal` but not
`eq` to the argument (the wrap copies it). `.todo/597` carries the list with the
measurements.

## Why it is a function

The rebuild is two `subseq`s, a `string` and two `%string-concat`s. `subseq` lowers to
an inline copy LOOP on both compile paths, so the arm was **~8 KB of wasm at every
site** -- and an array-only program paid it, because nothing in `(setf (aref m i) 0.0)`
tells the compiler `m` is not a string.

Measured on the wasm-GC backend at `--no-wasi --optimize`, one `(setf (aref m k) 1.0)`
site added to a `(make-array 16)` program: **8,615 -> 588 bytes**. On the JVM, one
`(setf (elt s i) v)` site: **5,042 -> 293 bytes**. `webgl-cube` is the extreme case --
25 sites across six `mat4-*` defuns, which held 203 of its 218 KB:

| program | flags | before | after | |
| --- | --- | ---: | ---: | ---: |
| `browser/webgl-cube/cube.lisp` | `--no-wasi --optimize` | 218,235 | 37,202 | **-83.0%** |
| `browser/webgl-platformer` | `--no-wasi --optimize` | 537,633 | 140,177 | -73.9% |
| `browser/webgl-galaxy` | `--no-wasi --optimize` | 57,148 | 25,620 | -55.2% |
| `browser/webgl-battlefront` | `--no-wasi --optimize` | 1,157,082 | 558,732 | -51.7% |
| `browser/webgl-robot-arm` | `--no-wasi --optimize` | 615,373 | 360,982 | -41.3% |
| `browser/hiragana` (`infer`) | `--optimize` | 1,263,046 | 1,232,436 | -2.4% |

**The crossover is one site.** A program with exactly one live site trades ~8 KB of
inline code for a ~665-byte function and comes out about even (`rainbow` +60 bytes, its
one site living in spliced library code). A program with no live site is unchanged, the
helper having been injected and then shaken out (`heat3d` +2 bytes of index-width
residue; `minesweeper`, `hello`, `greet`, `dice`, `triangle`, both `size-report` programs
byte-identical). Everything above two sites is pure win.

## The re-evaluation trigger

Two things would make this worth revisiting, and neither is "inline it back":

- **If `%schar-set-runtime` becomes hot.** The mutable-character-vector arm is now a
  call where it used to be an inline `%row-major-aset` -- one call per character
  written, in the `make-string` fill loops (ironclad's hex conversion is the shape).
  The answer would be a fast path at the SITE (a `ref.test` on the mutable-vector
  representation before the call), not a return to inlining the rebuild.
- ~~**If `subseq` on a string ever becomes one call on both compile paths.**~~
  **ANSWERED, and the answer is still `%subseq-core`.** `subseq` IS one call now
  (`%subseq-runtime`, `.kb/subseq-runtime.md`), but the rebuild here runs only where
  `%arrayp` said no, so `%subseq-core` reaches the string lane DIRECTLY while
  `%subseq-runtime` would re-test `stringp`/`%arrayp` on the way. The spelling stays.

What is left in a site after this is `%aset` itself, an inline
farray / packed-int-vector / general-array dispatch, which is the same shape of cost one
order of magnitude down. Its GENERAL arm has since become a call too
(`_arr_set`, `.kb/subseq-runtime.md`), taking a site from ~292 to 187 bytes; the packed
arms stay inline on purpose, the integer one being the fused raw-i64 store
(`.kb/packed-integer-vectors.md`), which a call would give up.

Same lesson, different mechanism, as `.kb/wasm-shared-coercion.md` (a wasm runtime
function emitted by the backend) and `.kb/format.md`'s `%fixed-decimal` (a compiler
primitive): when a per-site expansion grows past a few hundred bytes, it becomes a
callee. This one is a spliced Lisp defun, so the JVM and both wasm-GC backends get it
from one definition.

## Pinning tests

- `LispMacroExpanderTest.aStringWriteSiteIsOneCallAndNotAnInlinedSubseqConcatRebuild` --
  the site names `%SCHAR-SET-RUNTIME` and none of `SUBSEQ` / `%STRING-CONCAT` /
  `%ARRAYP`. It fails the moment the rebuild comes back inline, and that failure is the
  measurement above coming back.
- `LispMacroExpanderTest.theStringWriteRuntimeIsInjectedForAnArrayPlaceAndOmittedWithoutOne`
  -- the gate, in both directions.
- The behavior itself is pinned where it already was: the `setf-elt-cross-backend`
  ci-spec case, `LispEvaluatorTest.evalSetfEltDispatchesOverListStringAndVector`,
  `JvmLispCompilerTest.compileSetfEltOnAStringMutatesIt`, and
  `WasmLispCompilerIntegrationTest.compileSetfEltDispatchesOverListStringAndVector`.
- The LITERAL rule above: the `string-literal-write-cross-backend` ci-spec case (all
  four backends -- `eq`, the three place spellings, the argument case and the nested
  one), plus `LispEvaluatorTest.aStringLiteralIsSharedAcrossEvaluationsOnEveryBackend` /
  `#aWriteThroughAStringLiteralRebindsThePlaceAndLeavesTheConstant` /
  `#aWriteThroughAStringLiteralWithNoVariablePlaceIsAnError` /
  `#aWriteThroughAnAllocatedStringBufferIsStillInPlace` (the last one is the guard that
  the mark stayed on literals only and a `make-string` buffer is still written in place,
  alias included).
- The BULK rule: the `string-literal-bulk-write-cross-backend` ci-spec case (all four
  backends -- `replace`, `fill`, `(setf (subseq ...))`, `nstring-upcase`, and a
  `make-string` buffer as the guard), plus
  `LispEvaluatorTest.aBulkWriteThroughAStringLiteralLandsOnACopyAndLeavesTheConstant` /
  `#aBulkWriteThroughAnAllocatedStringBufferIsStillInPlace`.
- The `row-major-aref` place: the `row-major-aref-string-cross-backend` ci-spec case
  (all four backends -- the read, a literal write's rebind, a `make-string` buffer's
  in-place write), plus `LispEvaluatorTest.rowMajorArefReadsAStringLikeAref` /
  `#aWriteThroughAStringLiteralRebindsThePlaceAndLeavesTheConstant` (the
  `row-major-aref` row) / `#rowMajorAsetOnAStringLiteralAsAFirstClassCallIsStillAnError`,
  `LispMacroExpanderTest.theStringWriteRuntimeIsInjectedForAnArrayPlaceAndOmittedWithoutOne`
  (the `row-major-aref` row), `JvmLispCompilerTest.compileAndRunRowMajorArefReadsAndWritesAString`,
  `WasmLispCompilerIntegrationTest.compileRowMajorArefReadsAndWritesAString`.
