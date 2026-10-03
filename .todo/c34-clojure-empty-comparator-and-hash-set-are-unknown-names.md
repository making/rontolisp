# c34. Clojure: `empty`, `comparator` and `hash-set` are unknown names

Difficulty: Low

Unknown names at lower time (2026-10-03): `empty`, `comparator`, `hash-set`. Oracle (`clj` 1.12.6):
`(empty [1 2])` -> `[]`, `(empty {:a 1})` -> `{}`, `(empty '(1))` -> `()`, `(empty "ab")` -> `nil`,
`(empty (sorted-map-by > 1 :a))` -> `{}` keeping the comparator (`(assoc (empty sm) 1 :x 2 :y)` ->
`{2 :y, 1 :x}`), `(meta (empty (with-meta (sorted-set 1) {:a 1})))` -> `{:a 1}`;
`(sort (comparator >) [1 3 2])` -> `(3 2 1)` (`comparator` answers `-1`/`1`/`0` from a predicate);
`(hash-set 1 2)` -> `#{1 2}`.

`empty` of a sorted collection is `(:C%SORTED setp cmp #())` (`clojure.lisp`, "Sorted collections");
its arm belongs with the other sorted arms (`ClojureSortedArms`). Reference pages and catalog entries in
both doc trees, a `clojure-spec.yaml` case per verb.
