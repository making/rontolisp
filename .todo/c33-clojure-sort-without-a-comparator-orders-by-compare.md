# c33. Clojure: `sort` without a comparator should order by `compare`

Difficulty: Low

`sort` and `sort-by` without a comparator lower to an inline lambda over `<`, `string<`, `char<`
and keyword spellings (`ClojureFilterLowering.defaultCmpFn`/`defaultCmpBody`); anything else signals
`sort needs mutually comparable elements` (2026-10-03, all backends). The oracle (`clj` 1.12.6) sorts
by `compare`: `(sort [[1 2] [0 1]])` -> `([0 1] [1 2])`, `(sort [:b nil :a])` -> `(nil :a :b)`,
`(sort [true false])` -> `(false true)`, `(sort ['b 'a])` -> `(a b)`.

`compare` now exists (`rontolisp::%clojure-compare`, the order of the sorted collections), so the
default comparator can be `(minusp (%clojure-compare a b))`. Measure first: every program that sorts
without a comparator changes (the inline lambda against a call into a runtime that also carries the
keyword/symbol/vector arms). Drop the deviation line about `sort` in `doc/{en,ja}/clojure/deviations.md`
and pin the new kinds in `clojure-spec.yaml`.
