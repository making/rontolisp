# b03: Clojure seqs over vectors and lazy seqs

Difficulty: High

## Premise (measured, 2026-09-30)

The seq family runs over LISTS only (`first`=`car`, `rest`=`cdr`,
`count`=`length` being the exceptions); vectors-as-seqs need real design
(`.kb/clojure-frontend.md`, "Deviations"). Measured on the interpreter (Clojure
CLI 1.12 as oracle):

- `(concat '(1 2) [4])` answers `(1 2 3 . #(4))` here (CL `append` with a vector
  tail), `(1 2 4)` there.
- `(first [1 2])` / `(rest [1 2])` signal a type error here (`car`/`cdr` of a
  vector), answer `1` / `(2)` there.
- `(nth [10 20] 1)` works (lowered to `(NTH i coll)` since the spec), but `nth`
  as a VALUE is refused (`unknown name: nth` -- deliberately, since `#'NTH` has
  the operands backwards); the same holds for `quot` (now `truncate`).
- `seq`, `next`, `lazy-seq`, `range`, `take`, `drop`, `cons` onto a lazy seq are
  all `unknown name`; no program can spell an infinite seq.

Pinned by the `seq-ops-run-over-lists` case of `clojure-spec.yaml` (lists only,
all four backends).

## Shape

- Decide what a seq IS on this pipeline (a list view? a lazy struct with
  memoised head?): `first`/`rest`/`next`/`cons`/`concat`/`map`/`filter`/`reduce`
  over vectors (and later maps, [[b02]]) without breaking the list fast paths.
- `count` already takes any sequence; keep it the exception or unify it.
- Lazy seqs: memoisation + chunking (or an explicit decision for strict-only
  with named refusals for `lazy-seq`/`range`-without-end).
- `nth`/`quot`-as-values: synthesize the correctly-ordered function value
  (a `lambda` wrapping the primitive) instead of the bare `#'NTH`.

## Tests

- `clojure-spec.yaml` cases for vector seqs, `seq`/`next`, and a finite
  `range`/`take`; `ClojureSpecE2eTest` on all four backends.
