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

### Spike: the leaf-module lite (done, measured, sources restored)

Generated a lite `precomputed-tables.lisp` (the four tables + exclusions +
`*unicode-letters*` as literal data, 122 chunked data defuns, 401 KB of source)
and a lite `uax-15.lisp` (the `DerivedNormalizationProps` block replaced by the
1,344 source RANGE rows plus on-demand expansion inside
`get-illegal-char-list`), then swapped them into the quicklisp cache -- exactly
what `leafModuleForms` would do -- and measured `examples/db/postgres-hello.lisp`
end to end against live PostgreSQL:

| | real uax-15 | lite leaf modules |
| --- | --- | --- |
| interpreter run | 404 s | **0.36 s** |
| component compile | 34.7 s | **1.50 s** |
| component run (warm) | 28.4 s | **0.31 s** |
| component size | 8.60 MB | **6.66 MB** |

Output identical on the interpreter and the component (`((42 "hello"))`,
`((1) (2) (3))`). A separate 71-line normalization-vector program (NFD/NFKD/NFC/
NFKC over combining sequences, ccc reordering, compatibility ligatures, circled
digits, halfwidth katakana, Hangul jamo composition and precomposed syllables,
plus `get-illegal-char-list` lengths and endpoints for all four forms) is
**byte-identical between the real and lite builds** on all 60 normalization
lines and all 4 illegal-list lines -- including `(ILLEGAL :NFD 13233 (192 NIL)
(195101 NIL))`, i.e. the on-demand range expansion reproduces the upstream list
exactly. That program compiles in 0.33 s / runs in 1.17 s lite, against
34.3 s / 34.2 s real.

Three things the spike settled that were guesses before:

- **The JVM 64 KB method limit is real here.** The first generation put each
  table in one `dolist` over a literal and blew up at 134,021 bytes of method
  code (`.kb/jvm-method-size-limits.md`). Chunking the data into per-chunk
  defuns (250 entries each) fixes it and must be part of the design, not an
  afterthought.
- **The `*unicode-letters*` range loops are NOT a time problem.** Dropping all
  ~101k hardcoded CJK/Hangul/Tangut entries saves ~50 ms and 0.8 KB. The
  range-predicate rewrite is live-set hygiene for Finding 2, not a speed win --
  demote it accordingly.
- **Two latent bugs surfaced, both on the REAL path.** See below. Neither is
  caused by the lite tables; the lite build merely made them visible.

## Finding 3 -- `uax-15:unicode-letter-p` is WRONG today, on every backend

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

## Finding 4 -- a JVM operand-stack underflow the lite build exposes

With the lite modules in place, `(ql:quickload "cl-postgres")` compiled to a
`.class` dies:

```
while compiling lambda _lambda_2596: operand-stack model: underflow at 31 (opcode 0xb8)
```

Not caused by the lite tables themselves: the lite modules compile and run
correctly to a `.class` on their own, `(ql:quickload "uax-15")` lite compiles to
a `.class` fine, and `postgres-hello.lisp` with the REAL uax-15 compiles fine.
It needs lite + cl-postgres together, reproduces with `--no-prune` (at a
different lambda, `_lambda_2509`, underflow at 819, opcode 0xb6) and with
`--optimize`, and does NOT occur on the WASM component. So it is a latent bug in
`am.ik.jvm`'s emit-time operand-stack model (`.kb/jvm-operand-stack-model.md`)
that the changed code shape happens to reach. Root-cause it as part of this work
-- shipping the lite modules without it would trade one broken backend for
another.

**(C) Reduce allocation volume.** The cl-ppcre literal-pattern scanner cache
above is the concrete, library-independent piece: hoist a literal-pattern
`create-scanner` to a module-level constant at the call site (the compile paths
already have the machinery -- this is the same shape as
`CompileTimePathnameFolder`'s call-shape reduction). Worth doing regardless of
(A)/(B); ~1.8x on every regex call site.

## Phases

1. ~~**Lazy macro-time globals** (Finding 1).~~ **DONE 2026-07-26** -- thunk
   mechanism landed, divergence run down (a pre-existing emitter
   nondeterminism, not laziness -- see above), pinned by eight tests in
   `UserMacroExpanderTest` (an init that no macro reads never runs; the converse;
   transitive forcing; `defvar` idempotence over a pending expression;
   `defparameter` superseding it; the `#.` channel forcing; and both halves of
   the failed-init contract) plus five mechanism tests in `EnvironmentTest`.
2. **The uax-15 leaf-module lite (option B2)** -- spiked and measured above;
   what remains is productionizing it: derive the tables at compile time from
   the bundled data file rather than checking them in, chunk the data forms
   (mandatory, see the 64 KB limit), route it through
   `ShimLibraries.leafModuleForms`, and pin it with the normalization-vector
   program. Blocked on Finding 4.
3. **Finding 4 -- the JVM operand-stack underflow.** Gates phase 2.
4. **Finding 3 -- `*features*` fidelity** (`pushnew` invisible to the compile
   path entirely, and to the reader on both paths). Split into its own item;
   it is what breaks `unicode-letter-p`, and the lite build must not simply
   paper over it (the lite tables are correct BECAUSE they sidestep
   `char-from-hexstring`, which is luck, not a fix).
5. **cl-ppcre literal-pattern scanner hoisting** (Finding 2 / option C).
6. **Decide whether general (A) is still worth it** against the run time that
   remains after 1-5, and record the decision with its reason in `.kb/` so the
   next visitor can tell whether it still holds. The spike suggests it is not
   needed for THIS program (0.31 s warm run already), so (A) should be judged on
   its own merits for other libraries, not carried by uax-15.
7. **Re-measure and rewrite the docs.** PARTLY DONE: phase 1 falsified the
   "expect the first run to take minutes either way" sentence in
   `examples/db/README.md` and the header of `examples/db/postgres-hello.lisp`
   (compiling is now seconds), so both were corrected to say that the COMPILED
   paths are fast and the INTERPRETER is the slow one. Revisit after phase 2 --
   the interpreter's several minutes is uax-15's load, which the leaf-module
   lite removes, and the component's ~30 s run is Finding 2.

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
