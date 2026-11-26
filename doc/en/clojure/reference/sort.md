# sort

`(sort coll)` / `(sort comp coll)`

Answers the seq view copied and sorted as a list. Without a comparator numbers
sort with `<`, strings with `string<`, characters with `char<` and keywords by
spelling; anything else (or mixed kinds) signals instead of answering wrongly.
With one, its answer is read like the oracle's: a number puts the first argument
first when its integer part is negative (`compare`, `(- a b)`), anything else when
it is truthy (`<`, `>`). As a value a one- or two-argument lambda.

A `java.util.Comparator` orders by the sign of its `compare` instead: a host one
such as `(java.util.Comparator/naturalOrder)`, or a `reify` or `deftype`
implementing it. `sort-by` and the `sorted-map-by`/`sorted-set-by` constructors
take the same comparator. Calling a host comparator as a function signals, like
the oracle's. A host comparator exists on the interpreter and the JVM; a `reify`
or `deftype` one works on every backend.

```clojure
(println (sort [3 1 2])) ; (1 2 3)
(println (sort > [1 3 2])) ; (3 2 1)
(println (sort compare ["b" "a"])) ; (a b)
(println (sort (java.util.Comparator/naturalOrder) [3 1 2])) ; (1 2 3)
(println (sort (reify java.util.Comparator (compare [_ a b] (compare b a))) [1 3 2])) ; (3 2 1)
```
