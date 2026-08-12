# ANSI conformance: what the suite says to fix next

Difficulty: Medium

The ANSI Common Lisp test suite now runs against the interpreter
(`ansi-test/`, harness landed 2026-08-12). Baseline at suite revision
`ca06bd9`: **7,254 of 17,689 tests pass (41.0%)**, 3,370 return the wrong values,
7,065 signal. Re-measure with `ansi-test/measure.sh`; the full per-chapter table
and the ranked failure reasons live in `ansi-test/results/interpreter.md` and are
NOT duplicated here.

This item is the reading of that report: which gap is worth closing, in what
order, and which existing todo already owns it. Most of the missing operators
are already inventoried in the older extension todos -- what the suite adds is a
COST for each, so the inventory can be worked in payoff order instead of
alphabetically.

## Cheap and high-payoff (a single missing answer costs a whole chapter)

| what | costs | owner |
| --- | --- | --- |
| `find-symbol` has no status second value, so `cl-symbols.lsp` fails once per CL symbol | ~1,000 tests, the whole `symbols` chapter (4.2%) | .todo/214 |
| no runtime `make-package` and `defpackage` only as a LITERAL top-level form, so the chapter's own `set-up-packages` cannot be defined -- then the package-query API (`package-used-by-list`, `packagep`, `do-symbols`, `find-all-symbols`, ...) is missing on top | `packages` chapter (5.5%), 181 direct hits | .todo/038, .kb/packages.md |
| `read-from-string` has no index second value | most of `reader`'s 288 wrong-value tests | .todo/214 |
| the pathname accessors (`pathname-host`/`-device`/`-version`, `wild-pathname-p`, `pathname-match-p`, `enough-namestring`, `translate-logical-pathname`) and `*default-pathname-defaults*` | `pathnames` 9.8% + `files` 9.2% | .kb/pathnames.md |

## Operator families the suite bills by the dozen

Ranked by tests lost, all already listed in an existing todo -- the numbers are
the new information:

- set / tree operations on conses: `nunion` `set-exclusive-or` `nset-exclusive-or`
  `nintersection` `nset-difference` `subsetp` `sublis` `nsublis` `subst-if(-not)`
  `nsubst*` `tree-equal` `assoc-if-not` `rassoc-if-not` `member-if-not` `ldiff`
  `tailp` `nbutlast` `list-length` `get-properties` -- ~600 tests, `cons` at
  32.8% (.todo/033)
- bit-array operations `bit-and` `bit-ior` `bit-xor` `bit-not` `bit-nand`
  `bit-nor` `bit-eqv` `bit-andc1/2` `bit-orc1/2`, plus `bit-vector-p`
  `simple-bit-vector-p` `array-in-bounds-p` `upgraded-array-element-type` --
  `arrays` at 31.9% (.todo/043, .todo/180)
- the integer logical family `lognor` `logeqv` `lognand` `logcount` `logtest`
  `boole` `ldb-test` `deposit-field`, and `float-radix` `rational` `rationalize`
  `realp` -- `numbers` at 38.1% (.todo/037)
- complex numbers: 75 tests die on "complex numbers are not supported" and 55
  more on the `#C` reader syntax (.todo/037 -- currently scoped as "niche"; the
  suite disagrees, though only for conformance)
- the MOP-ish reflection `find-method` `remove-method` `compute-applicable-methods`
  `ensure-generic-function` `method-qualifiers` `next-method-p`, and
  `define-method-combination` -- `objects` at 34.4% (.kb/clos.md)
- stream constructors `make-two-way-stream` `make-echo-stream`
  `make-concatenated-stream`, `open`'s `:if-exists`/`:direction` beyond the
  native default, `with-hash-table-iterator` -- `streams` at 20.0% (.todo/036)
- the pretty printer `pprint-fill` `pprint-linear` `pprint-tabular`
  `pprint-exit-if-list-exhausted` `formatter` -- `printer` at 19.6%
  (.todo/001, .todo/041)

## Two failure shapes worth investigating as bugs, not as gaps

- **660 `IllegalArgumentException: ... expects keyword arguments ...`**: a
  sequence/cons function handed a keyword it does not know raises a raw Java
  exception. A conforming implementation signals a Lisp condition, and
  `handler-case` cannot see this one, so a test that expects a `program-error`
  fails twice over. The same shape covers `UnsupportedOperationException: setf
  does not support place: X` (101) and `IndexOutOfBoundsException` out of `aref`.
- **"Function expects 1 argument, got 2" (206 + the `X expects N arguments`
  rows)**: partly genuine arity checking, partly our functions being narrower
  than CL's (`&optional`/`&key` parameters we never took). Worth splitting the
  two before treating either as a gap.

## Reading caveat

2,229 top-level forms were lost (unreadable or unevaluable; none non-terminating),
and every test they would have defined is missing from the counts -- `objects`
(239) and `sequences` (903) are measured optimistically. Closing a gap can
therefore LOWER a chapter's pass rate by admitting the tests behind it; that is
progress, and the reason the report keeps the lost-form column next to the rate.
