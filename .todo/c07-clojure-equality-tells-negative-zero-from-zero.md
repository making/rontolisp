# c07. Clojure `=` tells -0.0 from 0.0

Difficulty: Low

Measured 2026-10-03, `(prn (= -0.0 0.0) (= 0.0 0) (contains? #{0.0} -0.0) (get {0.0 :z} -0.0))`:
the oracle (clj 1.12.6.1673) prints `true false true :z`, every backend here `false false
false nil`. `(zero? -0.0)` is `true` on both. Clojure's `=` on two doubles is numeric
(`Numbers.equiv`), so the zeros are equal there and `(hash -0.0)` is `(hash 0.0)` (both 0),
while `(= ##NaN ##NaN)` stays false and a double never equals a long. `%clojure-equal` falls
to CL `equal`, which is `eql` on floats.

## Plan

- `%clojure-equal`: two floats compare with CL `=` (the zeros equal, NaN unequal to itself),
  the category rule for a float against an integer unchanged.
- Map and set keys: `%clojure-table-key` must give -0.0 and 0.0 one key, or membership and
  lookup keep differing while `=` agrees.

## Pin

- clojure-spec: the four expressions above plus `(= ##NaN ##NaN)`, the oracle's output.
