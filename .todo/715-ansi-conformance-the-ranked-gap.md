# ANSI conformance: what the suite says to fix next

Difficulty: Medium

This item is the READING of `ansi-test/results/interpreter.md`: which gap is
worth closing, in what order, and which todo owns it. The numbers live in that
report and are not duplicated here -- only the ordering, the owner and the reason.

**Re-measure before you read.** `ansi-test/measure.sh` with no argument rewrites
the checked-in baseline; pass every chapter name instead and it writes
`results/partial.md` (a full run is ~8 minutes on 64 cores). Rank from
`results/logs/*.log`, not from the report's reason table -- see "How to count".

## Baseline (re-measured 2026-09-19, suite revision `ca06bd9`)

**15,090 / 19,472 tests pass (77.5%)** -- 1,707 wrong values, 2,675 signalled,
453 top-level forms lost. Against the 13,810 / 19,485 (70.9%) when this file
was last re-ranked, the suite gained ~1,280 tests as the number, byte-array,
stream-constructor and reader-syntax items below landed.

**Every per-row figure in an older table is dead; do not carry one forward.**

## How to count

The report's "Most frequent failure reasons" table merges two units: `ERROR
<test>` lines, one per TEST, and `%%%EVAL`/`%%%READ` lines, one per lost FORM
**per chapter** -- and the aux files are loaded by all 25 chapters, so one
failing aux form appears many times beside rows that are test counts. **Every
number below is TEST-level `ERROR`/`FAIL` lines from `results/logs/`.**

Even then a count is an UPPER bound, and it is blind in both directions:

- **Blind upward**: a test that stops erroring often fails for a SECOND reason.
- **Blind downward**: it can only count what is NAMED. A present-but-wrong
  operator is invisible.
- **Measure the effect as a DIFF of failing test NAMES, before and after**, and
  report fixed AND regressed. An ERROR-line census has disagreed with the real
  effect every time it has been checked.

## The ranking

### 1. The `universe.lsp` cascade is still ONE link, and it is out of scope

442 tests (256 `*MINI-UNIVERSE*` + 186 `*UNIVERSE*`) sit behind exactly one form,
still the largest single row on the board:

```lisp
(defparameter *methods*
  (list (find-method #'meaningless-user-generic-function-for-universe nil
                     (mapcar #'find-class '(integer integer integer)))))
```

`find-method` is **decided against** (`.kb/clos.md`, "Out of scope": classes are
compile-time-static and the dispatch tables depend on it). Revisit only if MOP
reflection is ever reopened -- and note that closing it ADMITS ~442 tests that
may then fail.

### 2. In-scope families, ranked by tests billed (2026-09-19)

| family | tests (per-test-name) | owner |
|---|---:|---|
| the stream surface: `open` 142 (`:direction` 49, `:if-exists` 42, `:element-type` 20, `cannot open` 6), residual composite constructors (`make-concatenated-stream` 18, `make-echo-stream` 12, `make-two-way-stream` 11), `clear-input` 11, `file-length` 16 / `file-position` 10 / `file-string-length` 6 | ~200 | `.todo/906` (file-stream residue; `.todo/387` closed with the constructors) |
| `subtypep` valid-p (still under-claiming for compound heads) | 140 | `.todo/214` (secondary values) / `.todo/035` (type system) |
| the printer surface: `print` 126, `write` 58, the `pprint-*` family 153 | ~200 | `.todo/041` (layout + `pprint-*` operators) |
| the array surface: `adjust-array` 96 (`:displaced-to` 40, non-adjustable 18), `make-array` 10 | ~100 | `.todo/905` (`:displaced-to`/adjustability residue; `.todo/043`/`.todo/180` closed without covering it) |
| `loop` | 97 | `.todo/029` (134 names left: validation ~30, hash/`across` destructuring, dotted `append`, typed init) |
| the reader surface: `read-preserving-whitespace` 27, `set-syntax-from-char` 12, `get-macro-character` 9, `name-char` 9 | ~60 | `.todo/214`, `.todo/041` (readtable half) |
| the runtime packages' missing MEMBER table -- `use-package` 21, `intern` 17, `with-package-iterator` 16, `unintern` 16, `find-symbol` 15, `shadowing-import` 13, `shadow` 12, `import` 14, all behind one cause | ~120 | `.todo/917` (`.todo/904` closed 2026-09-20 taking `packages` 35.2% -> 55.1% and measuring the rest down to this) |
| the sequence surface: `make-sequence` 39, `write-sequence` 33, `read-sequence` 29, `fill` 23 | ~120 | `.todo/006` / `.todo/031` |
| compile/loader introspection: `make-load-form` 41, `compile-file` 23, `disassemble` 14, `trace` 15 | ~90 | `.todo/042` |
| condition restarts: `restart-case` 19, `with-condition-restarts` 8 | ~30 | `.todo/039` |
| `copy-structure` (31 by reason -- the failing tests are named `STRUCT-TEST-*`, not `COPY-STRUCTURE`) | 31 | `.todo/907` |
| `boole` 13, `upgraded-array-element-type` 11, `formatter` 10, `pathname-match-p` 14 | ~50 | various |
| `#:test-not` keyword, `:allow-other-keys`, arity (`expects 1 argument, got N`) | ~60 | `.todo/006`, `.todo/031` |

### 3. Decided against -- do not read these as gaps

- MOP reflection: `find-method` (and the 442 above), `compute-applicable-methods`,
  `ensure-generic-function`, `add-method`, `remove-method`,
  `define-method-combination`. `.kb/clos.md`, "Out of scope".
- `slot-value` as a first-class function (53). It is in `CL_MACROS` by design;
  `SLOT-VALUE is a macro or special operator` is the model working.
- `compile-file`/`compile-file-pathname` (30): "no file compiler -- a program
  is compiled whole".
- `class-precedence-list-foo` (70): an aux form the suite builds with `#.`
  read-eval, not an operator. (`set-up-packages` was listed here too and was
  wrong about WHY: it is a plain defun whose body holds `defpackage` forms, and
  `.todo/904` made those evaluate.)
- `packages` at 35.2% is partly the DRIVER: it skips every `(in-package ...)`
  (`.todo/739` section 3). Settle 739 before treating that rate as a capability.

## The second instrument: the _Practical Common Lisp_ corpus

Peter Seibel's book code -- twelve ASDF systems, diffed byte for byte against
SBCL. Standing verdict in `.kb/asdf.md`, "The _Practical Common Lisp_ book
corpus": it ranks by WHETHER A PROGRAM RUNS AT ALL, while the suite ranks by
TESTS LOST. **An item both instruments name is the one to take first.**

As of 2026-09-08 the corpus needs no shim and names exactly one live gap:
**`.todo/041`'s missing right margin**, which three systems still differ by --
and which the suite independently ranks high (`printer`). On the corpus's axis
it is the last item standing.

## Reading caveat

453 top-level forms are still lost, so every chapter is measured optimistically;
`streams` (56) and `printer` (48) most of all. Closing a gap can LOWER a
chapter's rate by admitting the tests behind it -- that is progress.

`ansi-test/README.md`, "What the numbers are not": the suite tests full ANSI CL,
which rontolisp does not set out to be. A failing test is a statement about the
standard, not automatically a bug worth fixing. Section 3 above is that filter.
