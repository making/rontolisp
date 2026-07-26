# A `ql:quickload`-heavy program pays ~100x too much to compile and ~10x too much to run

Seed case: `examples/db/postgres-hello.lisp`. Its README and header comment
currently tell the reader to "expect the first run to take minutes"; that is a
symptom being documented as a property, and it is not one. Two independent
causes were measured, one per phase, and neither is specific to cl-postgres --
every program that quickloads a library with load-time table building pays them.

## Measured baseline (2026-07-26, warm `~/.rontolisp/quicklisp`, wasmtime 46.0.1)

`examples/db/postgres-hello.lisp`, live `postgres:17-alpine` on 54329:

| backend | compile | run |
| --- | --- | --- |
| interpreter | -- | 404 s (uax-15's load alone) |
| JVM (`-o Prog.class`) | 31.7 s (native binary) / 99.3 s (`java -jar`) | 3.5 s |
| WASM `--component --optimize` | 34.7 s (native binary) / 100.4 s (`java -jar`) | 28.4 s warm / 30.5 s cold |

Ablations that localize all of it to **uax-15** (pulled in by cl-postgres via
`saslprep`):

- `(ql:quickload "cl-ppcre")` alone: compile 0.2 s, module 1.57 MB.
- `(ql:quickload "uax-15")` alone: compile 34.3 s, module 4.58 MB, run 34.2 s.
- `(ql:quickload "cl-postgres")` + the two queries: compile 34.7 s, run 28.4 s.

So cl-postgres itself, cl-ppcre, split-sequence, ironclad, md5, cl-base64 and
alexandria together are noise; uax-15 is the whole bill on both axes.

Within uax-15's load (component backend, per-section timing with
`get-internal-real-time` marks inserted into a copy of `precomputed-tables.lisp`):

| section | ms |
| --- | --- |
| `package` + `utilities` + `trivial-utf-16` | 1 |
| `(defvar *unicode-data* ...)` -- 34,924 lines read + `cl-ppcre:split ";"` each, retained | 21,435 |
| the decomposition-map `loop` over `*unicode-data*` | 1,697 |
| `CompositionExclusions.txt` + `*canonical-comp-map*` | 3 |
| `*unicode-letters*` from the data + the seven CJK/Hangul/Tangut ranges | 106 |
| `normalize-backend.lisp` + `uax-15.lisp` (`DerivedNormalizationProps.txt`, 837 KB, with a quadratic `nconcf`) | 9,473 |

## Finding 1 -- compile time: every top-level `defvar` init runs in the macro-time interpreter

`eval/UserMacroExpander.registerMacroTimeDefinitions` evaluates
`defvar`/`defparameter`/`defconstant` EAGERLY into the macro-time
`LispEvaluator`, unconditionally, for every top-level form of the whole spliced
program. The documented reason is real but narrow: a macro body may READ a
global at expansion time (cl-who's `*html-mode*`), so the global has to have a
value by then.

For uax-15 that means `(defvar *unicode-data* (with-open-file ...))` parses the
whole 1.9 MB `UnicodeData.txt` and runs 34,924 `cl-ppcre:split` calls **in the
interpreter, at compile time** -- and then the compiled program does the same
work again at run time. Confirmed by thread-dumping a `-o` compile: the main
thread sits in `LispEvaluator.evalLet`/`evalWhile`/`apply` under
`UserMacroExpander.registerMacroTimeDefinitions` -> `evalResolved` ->
`evalDefvar` (LispEvaluator.java:3349).

The ablation ladder that pins it (all `-o ... --component --optimize`, native
binary, all reading the same baked 1.9 MB file):

| program shape | compile |
| --- | --- |
| `(defvar *u* (with-open-file ... collect line))` -- builtins only | 0.05 s |
| `(ql:quickload "cl-ppcre")` + the same, ppcre loaded but not called in the init | 0.32 s |
| `(let ((u (with-open-file ... collect (cl-ppcre:split ";" line)))) ...)` | 0.30 s |
| `(defvar *u* nil)` + `(setq *u* (with-open-file ... (cl-ppcre:split ...)))` | 0.28 s |
| `(defvar *u* (with-open-file ... (cl-ppcre:split ";" line)))` | **32.3 s** |
| same with `split-sequence:split-sequence` instead | 26.6 s |

`--no-prune` and dropping `--optimize` change nothing; the JVM backend costs the
same, because the cost is entirely in the shared CLI frontend. Compile time is
linear in the baked file size (0.33 s per 20,000-character chunk, i.e. ~17 s/MB)
purely because the interpreter re-reads it.

### Lazy macro-time globals -- LANDED 2026-07-26

`defvar`/`defparameter`/`defconstant` value expressions are registered as thunks
in the macro-time evaluator's global environment (`Environment.defineLazy`,
forced by `lookup`/`lookupOrNull`; `isBound` counts a pending thunk,
`define`/`set` discard it, and a non-forcing `hasBinding` serves the existence
probes in `boundp`/`find-symbol`). `LispEvaluator.registerLazyGlobal` is the
registration site: the name is proclaimed special eagerly, only the value waits.
A blanket SKIP (never evaluate) does NOT work and confirms the mechanism is
load-bearing: it dies on `CL-PPCRE::*STANDARD-OPTIMIZE-SETTINGS* is unbound`
(reached through the `#.` channel, not a macro body) and `MD5::*T* is unbound`.
Lazy forcing serves them for a few microseconds each while never touching
`*UNICODE-DATA*`.

Measured (same machine, `java -jar`, warm `~/.rontolisp/quicklisp`):

| program -> output | eager | lazy | bytes |
| --- | --- | --- | --- |
| `(ql:quickload "uax-15")` -> `.class` | 173.5 s | **3.6 s** | identical |
| same -> `.wasm` (Preview 1) | 157.8 s | **2.1 s** | identical |
| same -> `--component --optimize` | 102.9 s | **1.5 s** | identical |
| `postgres-hello.lisp` -> `.class` | 102 s | **6 s** | identical |
| `postgres-hello.lisp` -> `--component --optimize` | 123 s | **4 s** | identical |

(The postgres-hello rows are the CONTROLLED comparison: both sides carry the
determinism fix below, so they isolate laziness -- built by patching only the
`registerLazyGlobal` call site back to the eager `evalResolved` and rebuilding.
Against the UNFIXED jar the same program showed 1,560 / 1,177 differing bytes,
which is exactly what the spike saw and attributed to laziness.)

Ten probe programs (no defvar / pure defvar / macro-reads-a-global /
symbols+intern / defstruct+CLOS / cl-ppcre / cl-who / md5 / split-sequence /
macro-calls-a-defun), each compiled to BOTH a `.class` and a `.wasm`, are
byte-identical before and after.

**The gate the spike could not settle is settled, and it was a false alarm.**
The 1,147-byte divergence is NOT caused by laziness: the emitter is
nondeterministic across JVM runs, and always was. Compiling one four-line
program with a runtime `subtypep` FOUR times with the SAME unmodified jar
produces FOUR DIFFERENT modules. Cause: `LispMacroExpander.SUBTYPEP_PARENTS` was
declared with `java.util.Map.ofEntries(...)`, and `Map.of`/`Set.of` randomize
their iteration order once per JVM run (`ImmutableCollections.SALT`, seeded from
`System.nanoTime` at class-init). That order flows through `subtypepUniverse` ->
`subtypepAncestorTableForms` into the emitted `%subtypep-ancestor-table%`. The
postgres-hello diff is exactly that table: the SAME 1,954 emitted strings in a
different order (verified by disassembling both classes -- the multiset is
identical, only the sequence differs). uax-15 alone never emits the table, which
is why it was byte-identical either way. Fixed here by giving the lattice an
insertion-ordered map (`LispMacroExpander.orderedMap`); the broader question of
whether other `Map.of`/`Set.of` tables reach emitted output is being swept
separately.

## Finding 2 -- run time: wasm-GC allocation cost scales with the live set

The compiled component spends ~30 s re-doing at run time exactly what Finding 1
made the compiler do once already. That work is ~10x slower on wasm-GC than on
the JVM (3.5 s), and the reason is not cl-ppcre. Pure-rontolisp repro, no
library, `-o p.wasm --component --optimize`:

```lisp
(defun churn () (loop for j from 0 below 150 do (subseq "abcdefghij" 0 5)) 1)
(loop for i from 0 below 35000 do (churn))                  ; 0.50 s  -- nothing retained
(defvar *z* (loop for i from 0 below 35000 collect (churn))) ; 6.93 s  -- 35k conses retained
(loop for i from 0 below 35000 do (churn))                  ; 1.05 s  -- same loop, live set present
```

Allocating heavily while ANY sizable live set exists costs ~10x; the live set
does not even have to be large (8,750 retained conses already saturates the
penalty, and the scaling from 8,750 to 70,000 is roughly linear, not quadratic).
Retention alone is cheap (400,000 retained strings built with little garbage:
0.71 s) and allocation alone is cheap; it is the product that hurts. Not
affected by `-O gc-heap-reservation` / `-O gc-heap-reservation-for-growth`, and
identical on WASM Preview 1 and on `--component`, so it is neither the component
adapter nor heap sizing -- it is wasmtime's GC-heap allocator/collector, an
engine-level property in the same family as `.kb/wasm-function-body-size.md`.

The lever rontolisp controls is total allocation volume. Related measurement:
`cl-ppcre:split` with a LITERAL pattern string recompiles the regex on every
call. 35,000 splits of a 50-character line cost 3.22 s with the pattern string
and 1.75 s with a `create-scanner` result hoisted into a variable -- 1.8x, on
every `cl-ppcre:split`/`scan`/`regex-replace` call site in every program on
every backend. Upstream cl-ppcre gets this for free from its compiler macros
(`load-time-value (create-scanner ...)`), which rontolisp does not run.

## Options for the remaining run time, and the recommendation

**(A) Bake the macro-time value instead of re-running the init form, in
general.** The compiler already computes uax-15's tables (that IS Finding 1's
32 s). Emitting the computed value as data instead of emitting the init form
would take the component's run time to roughly module-load + query, for ANY
library, not just this one. It needs a deny-by-default serializability judgment
(streams, closures, identity, hash-table iteration order) in the spirit of
`AsdfSystems.evalDataForm` and the pure-config-setter walk, and it directly
TENSIONS with Finding 1's fix: lazy registration means the value is not computed
unless something asks. The honest framing is a fork: either the value is worth
computing once at compile time (A, keep evaluating but emit the result and
delete the runtime work) or it is not (Finding 1, do not compute it at all). Do
Finding 1 first -- it is safe, general, and its win is larger and unconditional
-- then decide A against the run-time number that remains. **(B2) below is this
same idea scoped to one known module, which is why it is the cheaper first
step.**

**(B) Substitute the two TABLE-BUILDING leaf modules with lite equivalents.**
This is the strongest option and the one to cost out first. The substitution
unit is not "uax-15" -- it is `precomputed-tables.lisp` and the load-time block
in `uax-15.lisp`, through the existing `ShimLibraries.leafModuleForms` seam.
Everything that computes a normalization (`decompose`, `canonical-ordering`,
`compose`, `nfd`/`nfkd`/`nfc`/`nfkc`, `normalize`, the Hangul algorithm) stays
VERBATIM upstream; only the way the tables are OBTAINED changes, and the table
contents are identical, so the normalization result is identical for all of
Unicode. That is a materially different bargain from a behavioral shim -- it is
a data-acquisition substitution, and it is the one place where the "keep the
library real" rule is not actually at stake.

What the API needs, versus what the load builds:

| global | entries | read by |
| --- | --- | --- |
| `*canonical-decomp-map*` | 2,061 | `decompose`, `get-mapping` |
| `*compatible-decomp-map*` | 3,796 | `decompose`, `get-mapping` |
| `*canonical-combining-class*` | 922 | `get-canonical-combining-class` |
| `*canonical-comp-map*` | 945 | `compose`, `get-mapping` |
| `*composition-exclusions-data*` | 81 | only to build `*canonical-comp-map*` |
| `*unicode-letters*` | 21,765 + ~101k from 7 hardcoded ranges | `unicode-letter-p` only |
| `*unicode-data*` | 34,924 rows | **nothing outside `precomputed-tables.lisp`** |
| the 4 illegal-char lists (`DerivedNormalizationProps.txt`, 9.5 s) | -- | `get-illegal-char-list`, which **nothing calls anywhere** (exported, zero callers in uax-15, cl-postgres, Postmodern or any other quicklisp system in the cache) |

So the 21.4 s section builds `*unicode-data*`, a value no consumer ever reads --
it is scaffolding for the four real tables. Those four total 7,724 entries and
encode to **~90 KB of literal s-expressions against 2.7 MB of bundled text**,
with no parsing, no `cl-ppcre`, and ~7,700 `gethash` writes (measured: 250,000
such writes cost 0.21 s on the component, so this is free). The 9.5 s section
builds a list for a dead function.

`unicode-letter-p` must keep working -- `postmodern/util.lisp` calls it, and
Postmodern proper is the declared follow-up to `.todo/115`. Cheap: keep the
21,765 data-derived entries and turn the 7 hardcoded CJK/Hangul/Tangut ranges
into range PREDICATES instead of ~101k hash entries (which also keeps them out
of the live set Finding 2 punishes).

Two sub-variants, and the choice matters:

- **B1 -- checked-in literal tables.** Simplest, but goes stale silently when
  the quicklisp uax-15 release bumps its `UnicodeData.txt`. Needs a loud guard
  (assert the bundled file's size/checksum, fail the compile on mismatch), or it
  becomes a wrong-answers-later trap.
- **B2 -- derive the tables at compile time from the bundled file and emit them
  as data.** No checked-in Unicode data, never stale, and it is option (A)
  restricted to ONE module whose value shape is known (integer-keyed hash tables
  of integers and integer lists), so it needs no general serializability
  judgment. This is the recommended target. Note the compiler ALREADY computes
  these tables today -- that is Finding 1's 32 s -- so B2 is largely a matter of
  emitting what it already has instead of throwing it away.

The variant to NOT do is the one this todo originally implied: an ASCII-only
`normalize`. `saslprep-normalize` returns early for printable-ASCII credentials,
so it is tempting to make `normalize` a stub -- but that silently loses NFKC for
non-ASCII passwords, i.e. a correctness regression in authentication. Off the
table.

### The derived tables -- LANDED 2026-07-26 (B2, as form-level rewrites)

`eval/Uax15Tables` + `ShimLibraries.rewriteComponentSource`, consulted by BOTH
loaders right after the component source is read (`LispEvaluator.loadFile` and
`cli.LoadInliner.spliceFile` each take the system name / component path now).
Mechanics and the full rationale: `.kb/asdf.md`, the "Derived uax-15 tables"
section.

The seam is NOT `leafModuleForms`. That one replaces a whole component with
canonical-shape (fully qualified) forms and deliberately bypasses package
resolution, so splicing the REAL uax-15 forms back through it would leave every
bare symbol unresolved. Rewriting the SOURCE instead keeps the file on the normal
read + `in-package` bracketing path, which is what makes a surgical replacement
possible at all: three spans change, and everything else -- every normalization
function, the `CompositionExclusions.txt` read, the `*canonical-comp-map*`
maphash, the seven hardcoded CJK/Hangul/Tangut letter range loops -- stays
verbatim upstream. Each span is located by a marker that must occur EXACTLY once;
an upstream release that moves one throws, naming the marker and the file (a
silent fallback to the real source would put the 30 s back with nothing pointing
at why). Missing data files (no filesystem) DO fall back, since there the real
source cannot work either.

Measured on `examples/db/postgres-hello.lisp` against live `postgres:17-alpine`
(`java -jar`, warm `~/.rontolisp/quicklisp`, wasmtime 46.0.1), all output
identical (`((42 "hello"))`, `((1) (2) (3))`):

| | before | after |
| --- | --- | --- |
| interpreter run | 404 s | **3.6 s** |
| JVM compile | 102 s (eager) / 6 s (phase 1) | **3.2 s** |
| JVM run | 3.5 s | **0.6 s** |
| component compile | 123 s (eager) / 4 s (phase 1) | **2.1 s** |
| component run (warm) | 28.4 s | **2.2 s** |
| component size | 8.60 MB | **5.98 MB** |

A 84-line normalization vector (NFD/NFKD/NFC/NFKC over combining sequences, ccc
reordering, compatibility ligatures, circled digits, halfwidth katakana, Hangul
jamo composition and precomposed syllables, the ccc map, and
`get-illegal-char-list` length + both endpoints for all four forms) is IDENTICAL
across the interpreter, the JVM, WASM Preview 1 and `--component`, and identical
to the real build's output on every line except the four `unicode-letter-p` lines
where the derived table is the CORRECT one (Finding 3). It runs in 3.7 s
interpreted against 404 s+.

Two things the spike had concluded that this reversed:

- **Chunked literal DATA DEFUNS are the wrong encoding, not a mandatory one.**
  They fix the 64 KB method limit and blow the 65534-entry CONSTANT POOL limit,
  which is Finding 4 (below). Bulk numbers are emitted as decimal runs inside
  string literals scanned by a generated helper: one pool entry per 18,000-char
  chunk instead of two per distinct integer.
- **`*unicode-letters*` keeps its CATEGORIES.** The spike stored `"Lo"` for
  everything (only `unicode-letter-p` reads the table, as a boolean). Emitting
  one range list per category costs ~2,000 more integers and loses nothing.

## Finding 3 -- `uax-15:unicode-letter-p` is WRONG today, on every backend

(Split out as `.todo/181` for the general `*features*` gap. The uax-15 victim is
no longer broken -- the derived tables never call `char-from-hexstring`, and
`Uax15E2eTest` now asserts `T` for `#\A` on all four backends -- but that is a
side effect, not a fix.)

The spike's only output divergence was `unicode-letter-p`, and the **lite build
is the correct one**: real says `(unicode-letter-p #\A)` -> NIL, and likewise for
`a`, `あ`, and U+D7A3. It answers YES only for characters inside the four
hardcoded CJK/Hangul ranges. Cause chain:

- `trivial-utf-16.lisp` pushes `:utf-32` onto `*features*` from an
  `(eval-when (:compile-toplevel :load-toplevel :execute) ...)`, keyed on
  `char-code-limit` (rontolisp: 1114112 = #x110000, so the right branch).
- That push never reaches the reader. Measured directly:

  | | interpreter | JVM compile path |
  | --- | --- | --- |
  | `pushnew` inside `eval-when` mutates `*features*` | yes | **no** |
  | plain top-level `pushnew` mutates `*features*` | yes | **no** |
  | a later `#+my-feature` in the same file sees it | **no** | **no** |

  The interpreter reads the whole file before evaluating any form, so a read-time
  conditional cannot see a feature the file itself pushes (real CL's `load` reads
  form by form and would). The compile path is worse: `pushnew` on `*features*`
  has no effect at all, so a compiled program cannot observe its own feature
  pushes even at run time.
- So `#+utf-32` is dead in `char-from-hexstring`, whose `let` binding degenerates
  to `(let ((char)) char)` -> **NIL for every input**.
- Therefore all 21,765 data-derived letter entries collapse onto a single NIL
  key: `(hash-table-count *unicode-letters*)` is 105,175 (the range entries) and
  `(gethash nil *unicode-letters*)` is `"Lo"`.

`postmodern/util.lisp` calls `uax-15:unicode-letter-p`, so this is a live
correctness gap on the declared follow-up to `.todo/115`, not a curiosity. The
`*features*` fidelity problem is the general bug and deserves its own item; the
uax-15 breakage is one victim.

## Finding 4 -- RESOLVED 2026-07-26: not an operand-stack bug, a constant-pool overflow

With the SPIKE's lite modules in place (the chunked literal data defuns),
`(ql:quickload "cl-postgres")` compiled to a `.class` died with

```
while compiling lambda _lambda_2603: operand-stack model: underflow at 31 (opcode 0xb8)
```

Reproduced, root-caused, fixed. It is not an `am.ik.jvm` operand-stack bug at
all -- the model was the messenger. The failing `invokestatic`'s constant-pool
operand was **296**, and entry 296 is a `Fieldref`; the model read that entry's
`Ljava/lang/Object;` as if it were an argument list, counted 17 arguments and
underflowed. The real index was **65832** (`0x10128`), and every emit site writes
an index as `(short) index`, so it was silently truncated to `0x0128` = 296:
`poolSize=65832` against the class-format limit of 65534. `~25,000` distinct
integer literals is enough on top of cl-postgres, because an integer literal is a
boxed `long` and a `CONSTANT_Long` takes TWO pool slots.

This is why the failure "needed lite + cl-postgres together", moved to a
different lambda under `--no-prune`, and never happened on WASM. The lambda it
landed in (cl-ppcre's `quote-substring`) had nothing to do with it.

Fixed at the source: `ConstantPool.add` now refuses the entry that would cross
`MAX_INDEX` (counting both slots of a long/double before accepting), so the error
names the real cause; the serialization-time check became an unreachable
backstop. `OperandStack.invoke` additionally rejects an operand whose pool entry
is not a method descriptor, so any OTHER way an index goes wrong is diagnosed
instead of mis-decoded. Pinned by `am.ik.jvm.ConstantPoolTest`; documented in
`.kb/jvm-method-size-limits.md` (new third-ceiling section), which also records
the design consequence: bulk data must be string literals, and per-chunk defuns
make the pool WORSE.

**(C) Reduce allocation volume.** The cl-ppcre literal-pattern scanner cache
above is the concrete, library-independent piece: hoist a literal-pattern
`create-scanner` to a module-level constant at the call site (the compile paths
already have the machinery -- this is the same shape as
`CompileTimePathnameFolder`'s call-shape reduction). Worth doing regardless of
(A)/(B); ~1.8x on every regex call site.

## Finding 5 -- `uax-15:get-mapping` fails on every backend (found 2026-07-26, not caused by this work)

Noticed while building the normalization vector, so it is recorded rather than
lost; it is NOT a regression from the derived tables (the real build fails the
same way).

`(uax-15:get-mapping :nfd)` signals on the interpreter and on the JVM, and the
interpreter's message is the honest one: `STRING cannot coerce 192 to a string`.
`get-mapping`'s local `to-str` is `(if (listp x) (coerce x 'string) (string x))`
and it is applied to the decomposition maps' KEYS, which are integers
(`parse-hex-string-to-int`) -- `(string 192)` is a type error in Common Lisp too,
so the function is broken upstream, not here. Nothing calls it (it is exported
API, no caller in uax-15, cl-postgres or Postmodern), which is why it went
unnoticed.

The part that IS ours: on the JVM the same program dies with a raw
`java.lang.ClassCastException: class [I cannot be cast to class
java.math.BigInteger` out of `_sub`, i.e. an `int[]` (a char vector's backing
store) reaches the numeric helper and a HOST exception escapes where a Lisp
condition is owed -- an `ignore-errors` around it would not catch it. The general
case is fine (`(- (list 1) 2)` signals a catchable `SIMPLE-ERROR` on both the
interpreter and the JVM), so this is specifically the char-vector representation
leaking into arithmetic. Small, but it is a cross-backend condition-system hole
worth its own item if it shows up again.

## Phases

1. ~~**Lazy macro-time globals** (Finding 1).~~ **DONE 2026-07-26** -- thunk
   mechanism landed, divergence run down (a pre-existing emitter
   nondeterminism, not laziness -- see above), pinned by eight tests in
   `UserMacroExpanderTest` (an init that no macro reads never runs; the converse;
   transitive forcing; `defvar` idempotence over a pending expression;
   `defparameter` superseding it; the `#.` channel forcing; and both halves of
   the failed-init contract) plus five mechanism tests in `EnvironmentTest`.
2. ~~**The uax-15 leaf-module lite (option B2)**.~~ **DONE 2026-07-26** --
   landed as form-level SOURCE rewrites, not leaf-module forms (the seam
   difference matters, see "The derived tables" above): tables derived at
   compile/load time from the bundled data, emitted as string-literal decimal
   runs, both loaders wired, marker guards throwing on an upstream move. Pinned
   by `Uax15E2eTest` on all four backends and `Uax15TablesTest` on the
   derivation. Remaining doc gap, PRE-EXISTING and not introduced here: uax-15
   has no row in the `doc/{en,ja}/guides/asdf-systems.md` loadable-library table
   and no `examples/asdf/` demo, unlike the ten libraries listed there (a gap
   left by todos 154/159). Adding it also means fixing that section's own
   "Nine"/"all ten" mismatch.
3. ~~**Finding 4 -- the JVM operand-stack underflow.**~~ **DONE 2026-07-26** --
   it was the 65534-entry constant-pool ceiling truncating emitted u2 indices,
   not an operand-stack bug; see Finding 4 above. It did not gate phase 2 in the
   end: the string-literal encoding phase 2 needs for other reasons also keeps
   the pool small, and cl-postgres compiles to a `.class` again.
4. **Finding 3 -- `*features*` fidelity** -- SPLIT OUT as `.todo/181`. The
   derived tables sidestep `char-from-hexstring`, so `unicode-letter-p` is
   correct now (and pinned), but that is luck: the next library that pushes a
   feature and reads it back still takes the wrong branch silently.
5. ~~**cl-ppcre literal-pattern scanner hoisting** (Finding 2 / option C).~~
   **DONE 2026-07-26** -- and NOT as a cl-ppcre-specific hoist. The essential fix
   was to stop dropping two CL facilities the library already uses to solve this
   itself: `define-compiler-macro` is now APPLIED at call sites and
   `load-time-value` now evaluates once per occurrence, on all four backends. See
   Finding 6 below and `.kb/compiler-macros.md`.
6. ~~**Decide whether general (A) is still worth it.**~~ **DECIDED 2026-07-26: no,
   and the cheaper idea underneath it was measured and rejected.** (A) has no case
   left for this program -- postgres-hello is 0.6 s on the JVM and 2.2 s warm on the
   component, and what remains is module load plus the string scan of the derived
   data, not re-executed library init. Judge (A) again on the next library with
   load-time table building.
   The "dead top-level `let`" rule proposed here rested on a FALSE premise:
   `LibraryDefunPruner` does not know `get-illegal-char-list` is unreachable and
   cannot, because it prunes only rontolisp's OWN bundled libraries and because a
   defun inside a `let` is not a definition to it at all (and phase 2 deleted that
   block anyway). Measured across every loadable library, the rule can delete two
   blocks -- cl-ppcre's and cl-who's `hyperdoc-lookup` -- worth **771 bytes of a
   1.55 MB module**, and neither passes the existing purity judgment, so collecting
   them would first mean widening `UserMacroExpander.isPure`. Reasoning recorded in
   `.kb/library-defun-pruning.md`. What DOES have numbers is the premise itself --
   pruning third-party trees at all, worth -3.0% on cl-ppcre with 20-23% of
   cl-postgres's and ironclad's source lines statically dead, and unreachable by
   `--optimize` -- filed as `.todo/183`.
7. ~~**Re-measure and rewrite the docs.**~~ **DONE 2026-07-26.**
   `examples/db/README.md` and the `postgres-hello.lisp` header no longer warn
   about the interpreter -- every backend runs it in a few seconds. The `.kb` files
   carry phases 2/3 (`asdf.md`, `jvm-method-size-limits.md`) and phase 5
   (`compiler-macros.md`, new). The loadable-library gap left by todos 154/159 is
   closed: uax-15 has a row in `doc/{en,ja}/guides/asdf-systems.md` (and the
   section's "Nine"/"all ten" mismatch is fixed -- it is eleven of each now), an
   `examples/asdf/uax-15-demo.lisp` matching `Uax15E2eTest`'s exercise, README rows
   in `examples/asdf/` and `examples/`, and the note that it is the one demo whose
   `--system-path` needs three directories. The stale "Interpreter only for now"
   header on `examples/asdf/cl-ppcre-demo.lisp` went with it.

## Finding 6 -- the scanner cost was two dropped CL facilities, not a missing hoist

Option (C) assumed rontolisp had to hoist the scanner itself. It does not: cl-ppcre
ships eight `define-compiler-macro`s that do exactly that -- `(constantp regex)` ->
rewrite the call so the pattern becomes `(load-time-value (create-scanner ,regex))`.
Both halves were parsed no-ops (`expandDefineCompilerMacro` returned nil;
`expandLoadTimeValue` returned its form, re-evaluated at every use), and either one
alone is worthless -- a compiler macro without a real `load-time-value` moves the
cost instead of removing it. Making both real is library-independent by
construction, and it turns on the compiler macros of every other loadable library at
the same time (ironclad, cl-utilities, md5, cl-who, jzon: 33 definitions, 15 of them
dropped by the reader's feature set, 11 that always decline, 5 that fire).

`.kb/compiler-macros.md` has the mechanics. The three properties that each caused a
real failure before being written down: decline (returning the `&whole` form, which
is a fresh cons) has to be detected by printed shape or the expander spins -- a
`StackOverflowError` on the interpreter and a silent hang with no diagnostic on the
compile path; a signalling body must be caught and the call left alone (CLHS permits
ignoring a compiler macro, which is what makes ironclad's `make-digest` safe); and
expansion-time output has to be muted on the interpreter too, because the compile
path's macro-time evaluator already swallows it and cl-utilities' `partition` macros
`warn` before declining.

Measured, `(cl-ppcre:split ";" line)` x 3,000 against a manually hoisted control:

| backend | before | after | hoisted control |
| --- | --- | --- | --- |
| interpreter | 47,800 ms | **6,608 ms** | 6,413 ms |
| JVM | 131 ms | **55 ms** | 13 ms (JIT noise at this size) |
| WASM `--component` | 246 ms | **136 ms** | 140 ms |

The todo's 1.8x understated the defect by an order of magnitude, because `split`
amortizes one scanner over 16 scans. Isolated: `(cl-ppcre:scan "..." line)` was 146x
the hoisted control on the interpreter, and `do-matches-as-strings` 80x. All three
compiled backends are now at or below the hoisted control on every shape.

The seed case was re-verified against a live `postgres:17-alpine` after the change:
`((42 "hello"))` / `((1) (2) (3))` on the interpreter (4.2 s), the JVM (0.6 s) and
`--component` (4.2 s cold), compiling in 2.3 s and 1.2 s. Preview 1 has no TCP by
design. `CiSpecE2eTest` is green on all four backends against the native binary.

One gap is left and it has a DIFFERENT cause, so it is `.todo/182`: the interpreter
re-expands a user macro on every evaluation, so a literal regex inside `do-matches`
gets a fresh `(scan ...)` cons per iteration, missing the per-call-site memos and
recompiling the scanner anyway (11.1 s vs 0.75 s over 500 iterations, against 11 ms
and 13 ms on the JVM and the component). Fixing it means memoizing macro expansion
by call-site identity in the interpreter.

## Notes worth not re-deriving

- The 1.9 MB / 837 KB data files are baked into the module by
  `cli/CompileTimePathnameFolder` as 20,000-character chunks reassembled with
  `(concatenate 'string ...)`. Baking itself is FREE: the same
  `with-open-file` + `read-line` loop with no library call compiles in 0.03 s
  and produces a 2.1 MB component. The chunking is not a suspect.
- `loop ... collect` is linear and fast on its own (35,000 collects of `(list i
  i)`: 22 ms). It only looks quadratic when the loop body also allocates
  heavily -- that is Finding 2, not a `loop` problem.
- wasmtime's module-compilation cache is warm after the first run and costs
  ~2 s wall on an 8.6 MB component; it is not where the 28 s goes.
- The interpreter's 404 s for the same load is consistent with the known
  ~10-minute figure and is the same work at interpreter speed. Findings 1 and 2
  do not address it; an interpreter-side fix is out of scope here.
