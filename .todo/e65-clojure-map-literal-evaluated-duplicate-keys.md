# e65. A Clojure map literal whose keys are equal only once evaluated keeps the last value

Difficulty: Medium

The source reader refuses a map or set literal whose READ key forms are `=` (the oracle's
reader, `ClojureReader.equivKey`). The oracle also refuses, at run time, a map literal whose
evaluated keys repeat (`clj` 1.12.6, 2026-10-08): `{(+ 1 2) 1 3 2}` and
`(let [a 1 b 1] {a 1 b 2})` throw `IllegalArgumentException: Duplicate key: 3` / `Duplicate
key: 1`; `{[1] :a '(1) :b}` fails at compile time with `Duplicate constant keys in map`. Here
all three answer a map with the last value. `(hash-map 1 :a 1 :b)` and `#{(+ 1 2) 3}` are
accepted by the oracle too (a set literal is checked at run time as well: measure it).

Also a runtime gap found on the way: `(= () [])` holds but `(get {[] 1} ())` answers `nil`
(the oracle: `1`), because the structural-key store tells the empty list and the empty vector
apart (`{() 1 [] 2}` is not refused by `read-string`).

## Plan

1. Measure which literal forms the oracle checks at run time (map, set, with and without
   constant keys) and the exact messages.
2. Check in the `%hash-map` / `%hash-set` lowering (constant keys at lower time, the rest at
   run time), on all four backends; a clojure-spec line.
3. Make the empty list and the empty vector one structural key; a clojure-spec line.
