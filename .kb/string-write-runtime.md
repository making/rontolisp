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

**The OUT-OF-MODEL boundary set, enumerated (third round, after the consolidated
pass caught the FFM and Objective-C bridges).** Every place a compiled program hands
a string to something outside the Lisp value model, with its normalization status:

- `ffi:` (FFM) -- `JvmFfiTemplate.lispString` renders through a REFLECTIVELY BOUND
  `_strv` (`strvMethod`, looked up beside `_apply` in `bind`; absent exactly when the
  program has no array runtime, which is exactly when no character vector can exist).
  Every string the bridge accepts -- `ffi:open`/`ffi:symbol` names, `:string` call
  arguments, `bufferBytes` -- funnels through it. The reflective hook, not an
  in-template renderer, because `_strv` is the single authority on the representation
  (headers, fill pointers, displaced views) and the template travels: duplicating the
  walk in an embedded class is drift waiting to happen, and adding a helper class to
  the blob would mean travel-list and verifier-order changes for nothing.
- `objc:` -- `JvmObjcTemplate.lispString`, the identical hook (class names,
  selectors, `objc:string` content, `objc:data` buffers). The appkit/metal/scene
  libraries are Lisp over these verbs and are covered by the same funnel.
- `java:` interop -- `JavaBridgeTemplate.lispString` (class/method/field names) AND
  `marshal` (the single source of truth for every ARGUMENT position, fixed arity,
  varargs and constructors), both rendering through the same hook.
- `uiop:getenv` -- the JVM `%host-getenv` lowering mints `_strv` before its
  `(String)` cast (`JvmGetenvCompiler`); the WASM one renders before `_getenv`'s
  `TYPE_STRING` read (`WasmGetenvCompiler`).
- The `equalp` hash-key fold -- the key renders BEFORE the fold on both compiled
  backends (`JvmHashRuntimeBuilder`'s `_hashKey` before the travelling
  `RontoHashTable.equalpKey`, which knows the value model but deliberately not the
  array representation; `WasmEqualpKeyRuntimeBuilder` at the fold's entry), so a
  producer-built key collides with the literal spelling. `equal` tables were already
  covered by `_hash`/`_equal`'s entry normalization.
- jvm-export (and the Maven plugin / servlet entry points over it) -- `_exStr`
  renders before its frame check; the request/response transport boundary renders in
  Lisp (`%http-header-name`/`-value`/`%http-join-strings`).
- File system and streams -- `JvmIoRuntimeBuilder`'s strip-quote sites, `load`,
  sockets, fetch: all mint `_strv` (the earlier rounds).
- WIT / component -- `emitStageStringParam` and `_str_to_mem` normalize every host
  crossing; `%str-byte-*` the socket bytes.
- GPU (`am.ik.gpu` / the linalg kernels) -- CANNOT receive a Lisp string: the device
  seam is numeric end to end, and `--gpu` device selection is CLI-level.
- `Runtime.exec`-shaped calls -- none exist in the emitted runtimes.
- `symbol-name`/`intern`/`make-symbol` -- normalize at their compile sites
  (`JvmSymbolApiCompiler`, the WASM intern funnel).

Pinned by `JvmFfiInteropCompilerTest.aProducerBuiltStringCrossesTheFfiBoundary`,
`JvmObjcInteropCompilerTest.aProducerBuiltStringCrossesTheObjcBoundary`, the
producer rows in `JvmJavaInteropCompilerTest.staticCall`, the
`compileAProducerBuiltStringFoldsAsAnEqualpHashKey` twins, and the equalp row of the
`string-identity-cross-backend` ci-spec case; `examples/jvm/cffi-sqlite.lisp` and
`examples/macos/system-frameworks.lisp` are the end-to-end canaries.

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

## The third round: the trim family, map/coerce, getenv (2026-08-31, `.todo/600`)

**Invariant: a string the program allocates through `string-trim` /
`string-left-trim` / `string-right-trim`, a PROGRAM-WRITTEN `(map 'string ...)` or
`(coerce seq 'string)`, `uiop:getenv`, or the first-class `#'concatenate` wrapper is a
MUTABLE sequence with identity on ALL FOUR backends, exactly like the producers above.**
Pinned by the third block of the `string-identity-cross-backend` ci-spec case and the
extended `LispEvaluatorTest.aFlippedStringProducerResultHasWritableIdentity` /
`JvmLispCompilerTest`/`WasmLispCompilerIntegrationTest.compileAFlippedStringProducerResultHasWritableIdentity`.

Same mechanism as round 2 -- one `_toMutStr` / `_to_mut_str` at the site, under the
shared `MutableStringProducers` gate, which the round joined by name (`string-trim`
family, `%host-getenv` -- the spliced defun `uiop:getenv` is Lisp over, seen by the scan
because it runs with the libraries already spliced) and by SHAPE
(`isMapToString` / `isCoerceToString`, a LITERAL `'string` result designator). Two
things this round had to settle that the earlier ones did not:

- **`(coerce x 'string)` over a STRING answers the ARGUMENT** (CLHS: an object already
  of the type is returned as is; `%seq-string`, concatenate's per-argument normalizer,
  depends on it). So the wrap sits on the BUILD arm only: the expansion is
  `(if (stringp x) x (%str-fresh <build>))`, never a wrap around the whole call.
- **A sequence operator's own result conversion is NOT a program-written `coerce`.**
  `reverse` / `remove` / `remove-if` / `remove-duplicates` / `substitute` /
  `substitute-if` / `sort` all lower through `seqResultDispatchForm`, whose string arm
  is a literal `coerce` form, and `map 'string` is what every `coerce`-to-string builds
  with. Those generated conversions now carry the INTERNAL designator
  `LispNames.SEQ_STRING_RESULT` (`%seq-string-result`), which reads as `string`
  everywhere the two expansions look at a result type and skips exactly the wrap -- so
  the family keeps the immutable result it has always had, on every backend, whatever
  else the program contains.

`%str-fresh` -- the fold's fresh-string constant spelling -- is the Lisp-level name for
that wrap and gained two more callers here: the `coerce` build arm above and
`BuiltinFunctionWrappers.concatenateWrapper`'s `%string-concat` reduce, which is how
`(funcall #'concatenate 'string a b)` came to answer what the call-position spelling
answers. The interpreter binds it as a copy (`Environment`), for the wrapper bodies it
can evaluate. **`#'format` needed nothing**: its wrapper renders through `%fmt-render`,
whose `%fmt-cat` is a `concatenate 'string`, so round 2 had already flipped it --
measured, not assumed (`.todo/600` had listed it as still immutable).

**`read-line`'s eof-value comes back by IDENTITY.** `(read-line s nil eof)` lowers to
`(or (read-line s) eof)`, and the wrap used to sit outside the whole rewrite, so a
STRING sentinel was handed back as a mutable COPY of itself -- visible on both WASM
backends (`eq` is object identity there; the interpreter and the JVM compare string
CONTENT, `.todo/444`, which is why the bug could only be seen on two of the four). The
rewrite is now compiled from the `read-line` case itself and only the inner one-argument
call wraps.

### What did NOT flip, with the number that says why

- **`reverse` / `remove` / `remove-if` / `remove-if-not` / `remove-duplicates` /
  `substitute` / `substitute-if` / `sort` over a string.** They share the lowering
  above, so flipping them means putting their names in the producer gate -- and the gate
  cannot see whether the sequence is a string. A program that reverses a LIST would pay
  the JVM array runtime the wrap needs: measured 2026-08-31, `(print (reverse (list 1 2
  3)))` 14,674 -> 21,664 bytes of class (**+6,990, +47.6%**) and `examples/console/nqueens`
  17,927 -> 24,662 (**+6,735, +37.6%**), against +164 bytes of wasm (+0.7%) in both. The
  flat part of that is the array gate itself (`usesArrays`, which the wrap must join --
  a character vector has to be printable, indexable and comparable wherever it flows):
  forcing the gate on costs hello_world +2,357, pi_approx +3,173, hanoi +3,011,
  contact-book +3,297 bytes of class and 0 bytes of wasm. **Re-evaluation trigger:** a
  JVM shape where a character vector can exist without the general-array runtime, or a
  gate that can tell a string sequence from a list one.
- ~~**`princ-to-string` / `prin1-to-string` / `write-to-string`.**~~ **CLOSED
  2026-09-01 by the fourth round (below), in exactly the shape this bullet named.**
  The tax the earlier round predicted is real and is NOT only format's: the expander builds pieces with
  `princ-to-string` at ~25 sites, including `map 'string`'s per-ELEMENT accumulator
  (`(cons (princ-to-string call) acc)`), so wrapping the shared compiler case wraps one
  character at a time. Measured 2026-08-31 with the naive wrap (min of two passes,
  controls unmoved), WASM: string-trim +80%, coerce-string +54%, map-string +35%,
  concatenate-of-a-list +47%, reverse-string +38%, string-upcase +50%, format-nil +40%,
  fmt-render +17%, json-stringify +6%; JVM: format-nil +17%, fmt-render +12%,
  string-upcase +24%, concat-list +52%. Sizes barely move (zlib +60 bytes of wasm,
  hello_world / pi_approx identical) -- this one is paid in TIME. **The shape that would
  work** is the internal piece alias the todo names: one print-object-dispatching alias
  the ~25 expander sites use, with the public name wrapping; `StringValuedForms`'s
  `ALWAYS_STRING` entries would move to the alias with them.
- **A COMPUTED `format` destination that is nil at run time**, and a COMPUTED
  `(coerce x ty)` whose `ty` is `'string` at run time (`expandComputedCoerce` routes to
  the unwrapped shared `%seq-to-string`). Both are gate problems of the same shape as the
  first bullet: gating every `(format <expr> ...)` / `(coerce x <expr>)` program buys the
  rare nil-at-run-time case and pays the array gate's +2.4 to +3.3 KB of class in every
  program that formats to a stream.
- **`symbol-name` / `(string 'sym)` / `gensym` / `make-symbol` names** -- deliberate, and
  unchanged (CLHS leaves symbol-name mutation undefined; SBCL shares the name object).

### What it costs, measured 2026-08-31 (Apple M4 Max, one locked run, min of two passes)

Sizes, `--optimize` wasm and the `-o X.class` JVM output, before -> after: hello_world
595 / 2,660, pi_approx 4,995 / 8,777, zlib 128,251 / 160,464, word-frequency,
contact-book, nqueens, count-vowels -- **all byte-identical**. The one program that moved
is `examples/console/calc` (a `string-trim` reader): wasm 184,119 -> 184,387
(**+268, +0.15%**), class 197,455 -> 197,543 (+88, +0.04%).

Times, each row its own defun, `control-int` and `control-mapcar` untouched by the round
(ms, 1,000-char inputs):

| row | JVM before -> after | WASM p1 before -> after |
|---|---|---|
| control-int (2M) | 1 -> 1 | 7 -> 7 |
| control-mapcar (200k) | 17 -> 11 | 6 -> 6 |
| string-trim (20k) | 47 -> 189 | 110 -> 248 |
| coerce-string (5k, 500 chars) | 165 -> 182 | 247 -> 261 |
| map-string (2k) | 357 -> 360 | 266 -> 281 |
| concatenate of a list (5k) | 101 -> 76 | 270 -> 267 |
| reverse of a string (2k) | 81 -> 111 | 275 -> 272 |
| json-parse / json-stringify | 32 -> 34 / 99 -> 102 | 87 -> 85 / 109 -> 110 |
| format-nil / fmt-render / upcase | flat | flat |

**`string-trim` is the row that moves**, and it moves for the reason every flipped
producer moves: the result is now a boxed character vector instead of a `substring` of
the source. Per call that is 7.1 us on the JVM and 6.9 us on WASM for a 1,012-character
string -- *less* than the `string-upcase` of the same string that round 2 already ships
(16 us), which is what says the cost is the representation and not the trim. The wasm
controls did not move at all; the JVM and interpreter rows carry a +-20-35% noise floor
this machine gives them (`control-mapcar` moved -35% on the JVM between the two jars),
so only the wasm column is worth reading below that size.

The `concatenate` rows are FLAT, which is the internal-designator decision paying off:
`%seq-string` normalizes every non-string argument through
`(coerce x '%seq-string-result)`, so the wrap never lands in front of `%string-concat`.

### The out-of-model boundaries this round exposes, enumerated and checked

The round adds character-vector DENSITY, not a new boundary kind -- round 2's set is
still the set -- but `uiop:getenv` is the first HOST READ to answer one, so each was
re-run rather than argued (one program, all four backends, byte-identical output):
getenv -> `concatenate` -> pathname -> `open`; a `(coerce chars 'string)` and a
`string-trim` result as a pathname; a `map 'string` key in an `equalp` table and a
`coerce` key in an `equal` one; `intern` of a trimmed name; `string=` / `length` /
`subseq` / `print` / `write-line` over each producer's result; the `#'concatenate`
wrapper's result through the same. `ffi:` / `objc:` / `java:` need no new row: their
`_strv` hook is keyed on the REPRESENTATION, so a getenv- or trim-built character vector
takes the identical path a concatenate-built one already does (`JvmFfiInteropCompilerTest`
/ `JvmObjcInteropCompilerTest` / `JvmJavaInteropCompilerTest` are green). The HTTP
transport renders in Lisp (`%http-header-name` / `-value` / `%http-join-strings`), which
is representation-blind for the same reason.

`StringValuedForms.ALWAYS_STRING` was RE-CHECKED against the flip and needed no change
in this round: none of `%fixed-decimal` / `%string-concat` / the four print-to-string
entries was flipped, and that is exactly why the print family was the expensive bullet
above. (The fourth round then moved the two PUBLIC print entries out and put the piece
aliases in -- see below.)

**What is STILL immutable after the fourth round, and why** (each a measured decision
with its re-evaluation trigger; `.todo/600` closed with this list and none of it is
open work):

- `reverse` / `remove` / `remove-if` / `remove-if-not` / `remove-duplicates` /
  `substitute` / `substitute-if` / `sort` over a string -- the gate cannot see the
  sequence type (the first bullet above, with the nqueens number). **Trigger**: a JVM
  shape where a character vector can exist without the general-array runtime, or a
  gate that can tell a string sequence from a list one.
- A COMPUTED `format` destination that is nil at run time, and a COMPUTED
  `(coerce x ty)` whose `ty` names a string at run time -- only the literal spellings
  are producers; the wrap itself would be CORRECT for both (a non-string passes
  `_toMutStr` through), it is the gate that costs (+2.4 to +3.3 KB of class per
  `(format stream ...)` program). `expandComputedCoerce` routes to the unwrapped
  shared `%seq-to-string`, the one line to change if the gate question is answered.
- `symbol-name` / `(string 'sym)` / `gensym` / `make-symbol` names -- CLHS leaves
  symbol-name mutation undefined and SBCL shares the name object. (The INTERPRETER
  mutates the symbol's own name through such a write, a separate undefined-behavior
  wart nobody depends on.)
- fetch / socket / gray-stream read results, `%io-read-line`'s socket arm included;
  only the `%read-line-raw` fallback wraps.
- json-parse's multi-fragment string values (`%json-concat` merges through the
  unwrapped `%string-concat`; single-fragment values are subseq slices and already
  mutable). If value identity ever matters, wrap in `%json-string`'s return, not in the
  merge -- the merge re-conversion was the 112 -> 245 ms json-stringify regression
  before 596 moved it.
- Every string PIECE the expander builds with `%princ-piece` / `%prin1-piece` (the
  fourth round, below): internal by construction, never the program's value.

## The fourth round: the print family (2026-09-01, `.todo/600`, closed)

**Invariant: a string the program allocates through `princ-to-string` /
`prin1-to-string` / `write-to-string` is a MUTABLE sequence with identity on ALL FOUR
backends, exactly like the producers above -- a `print-object`-routed rendering
included. A string PIECE the codegen's own expansions build with the same conversion
(every `format` directive, `map 'string`'s per-element accumulator, a condition's
default message, a computed `gensym` name, the type name inside `#<...>`) is NOT a
producer and stays an immutable internal value.** Pinned by the fourth block of the
`string-identity-cross-backend` ci-spec case, the extended
`LispEvaluatorTest.aFlippedStringProducerResultHasWritableIdentity` /
`JvmLispCompilerTest`/`WasmLispCompilerIntegrationTest.compileAFlippedStringProducerResultHasWritableIdentity`,
their `compileAPrintObjectRoutedPrincToStringResultHasWritableIdentity` twins, and
`LispMacroExpanderTest.anExpanderBuiltStringPieceIsTheInternalConversionNotThePublicProducer`
(the spelling guard: if a public name comes back into an expansion, the tax below comes
back with it).

**The shape is the one the third round named: an internal, print-object-DISPATCHING
alias.** `%princ-piece` / `%prin1-piece` (`LispNames.PRINC_PIECE_INTERNAL` /
`PRIN1_PIECE_INTERNAL`) are the public conversions minus the wrap:

- `expandPrintObjectHook` rewrites them exactly as it rewrites the public names
  (`(%print-object-str x escape)`, or `%print-cased` under `*print-case*`), so a piece
  that renders a user instance still consults the method. The raw `%princ-to-string` /
  `%prin1-to-string` could not serve here: they are print-object-FREE by design
  (`.kb/clos.md`, the fallback that must not re-enter the rewrite).
- `Jvm`/`WasmExprCompiler` compile the piece names through the same
  `compilePrintOperator` and stop; the three public names do the same and finish with
  `_toMutStr` / `_to_mut_str`, under the shared `MutableStringProducers` gate, which
  they joined by name. A `format` to a stream stays out of the gate, as before: its
  pieces are `%princ-piece` forms.
- The interpreter binds the piece names as the same two functions (`Environment`) and
  routes them through the operator seam (`LispEvaluator.evalConsRareOperator`), so a
  `defmethod print-object` that follows the first print is seen by a piece too.
- Every expander site moved: `opsToPieces`'s `~a`/`~s`/`~w`/`~d`/`~c`/`~f`,
  `decimalExpr`, `radixIntegerExpr`, the `~e` mantissa/exponent, `generalFloatExpr`,
  `fmtPadChar`, `printPiece`'s two-element shape test (a `(%princ-piece x)` under a
  stream destination still prints straight through `princ`), `expandMap`'s STRING
  accumulator, `strictStringDesignatorForm` (the computed `(string x)`), the
  condition-message sites of the signal family, `typeNameOf`, `expandComputedGensym`.
  The spliced Lisp libraries moved with them: `format-render.lisp` (the runtime
  `%fmt-render`), json.lisp, url.lisp, sockets.lisp, stdin.lisp, http-server.lisp,
  gray.lisp's print dispatch, and the `print-object` methods of torch / geom / jzon.
  None of those strings reaches the program: each is appended by `%string-concat`,
  written to a stream, or stored where the program reads it back through a public
  producer.
- `StringValuedForms.ALWAYS_STRING` swapped the two public entries for the piece
  names -- the public names can now answer a character vector, and an entry that does
  would silently drop a normalization a consumer needs.
- `PureBuiltinFolder` folds the public names to `(%str-fresh "...")` (a
  per-evaluation mutable copy, like the other fresh-string producers) and the piece
  names to a plain literal; both are blocked under `*print-case*`, as the public
  names already were (`.kb/pure-builtin-fold.md`).

### What it costs, measured 2026-09-01 (Linux x86-64, one machine, min of two)

Each row its own defun, the two jars over one program, JVM class and WASM p1, ms.
**The rows that do NOT name the public print family compile to BYTE-IDENTICAL
output** -- an `iso` program of the map / coerce / fmt-render / upcase / trim /
format-to-stream rows is `cmp`-equal on both backends -- so their timing deltas on this
machine (up to +-15% on WASM, +-35% on the JVM between runs) are its noise floor and
not the change. The rows that do name it:

| row | JVM before -> after | WASM p1 before -> after |
|---|---|---|
| control-int (2M) / control-mapcar (200k) | 21 -> 19 / 29 -> 28 | 7 -> 6 / 3 -> 2 |
| princ-to-string of an integer (100k) | 46 -> 83 | 13 -> 27 |
| princ-to-string of a 1,000-char string (20k) | 151 -> 534 | 305 -> 710 |
| prin1-to-string of a 1,000-char string (20k) | 167 -> 516 | 307 -> 745 |
| format nil / `%fmt-render` / format to a stream | flat | flat |
| map 'string / coerce 'string / concatenate of a list | flat | flat |
| string-upcase / string-trim / reverse of a string | flat | flat |
| json-parse / json-stringify | flat | flat |

Per call that is +0.4 us (JVM) / +0.14 us (WASM) for an integer and ~19-20 us for the
1,000-character string on both -- the same representation cost the trim and case rows
pay, one boxed character vector instead of one `String`. The 17-80% the naive wrap had
measured on the string-building family did not come back, which is the whole point of
the alias.

Sizes (`--optimize` wasm / `.class`): hello_world, pi_approx, zlib, calc, contact-book,
nqueens, word-frequency, hanoi, error-handling -- **all byte-identical** (none of the
console corpus names the public print family; their `format`s are pieces). A program
whose ONLY producer is `princ-to-string` joins the gate: `(defun f (x) (princ-to-string
x))` 11,758 -> 12,110 bytes of wasm (+352) and 4,041 -> 7,837 of class (+3,796 -- the
array runtime the wrap needs, the flat cost every first producer pays). A `format
nil`-only program is byte-identical; a program that already carried a producer pays the
one call site (+2 bytes of wasm, +3 of class); `examples/jvm/java-interop` +3 bytes of
class.

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
