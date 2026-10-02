# b81. `for`/`doseq` step through lazy tails (strict fix)

Difficulty: Medium

Measured 2026-10-02, oracle `clj` 1.12.6.1673 vs exec jar at `ec836db36`:

- `(println (for [[idx elt] (map vector (iterate inc 0) "zzab")] idx))`
  oracle `(0 1 2 3)` / ronto `Unhandled condition: seq needs a collection`.
- `examples.test.lazy-index-of-any`: oracle `Ran 1 tests containing
  4 assertions. 0 failures, 0 errors.` / ronto `1 failures, 2 errors`
  (`seq needs a collection` twice plus the `with-out-str` line-count diff).
- Root cause: `for`/`doseq` lower to one-shot `dolist` over
  `(%clojure-seq coll)` (`ClojureLoopLowering.java:326-328`,
  `ClojureSeqLowering.java:313-315`), which realizes exactly one level. A
  realized lazy cons's cdr is another `(:C%LAZY cell)` wrapper
  (`clojure.lisp` map-step/concat-step), which `dolist` then traverses as
  ordinary list structure; vector destructuring of `:C%LAZY` falls through to
  `%clojure-strict-seq`'s `seq needs a collection` error.
- Controls pass on both: `seq`/`first`/`take`, `for` over strict or `take`n
  input, set-as-function, `with-out-str` capture itself.

## Plan

- Step `doseq`/`for` through lazy tails one level at a time (a labels loop
  over `%clojure-seq`/`cdr`) instead of the one-shot `dolist`, staying
  strict (the documented strict-`for` row does not move in this item).
- Fixes the 2 errors and the wrapper-internals garbage; the `with-out-str`
  line-count FAIL remains (that is b82's lazy-`for` semantics).
- Pure lowering change; no new runtime shape (reuse the seq view + lazy
  realizers every backend already compiles).

## Pin

- `clojure-spec.yaml` (all four backends): `for-over-lazy-map`,
  `doseq-does-not-yield-lazy-internals` (the `(0 1 2 3)` shape above,
  destructured, with `:when`/`:let`).
- `ClojureLoweringTest`: the stepped lowered shape.
- E2E: `examples.test.lazy-index-of-any` errors gone (the 1 line-count
  failure remains and names b82).
