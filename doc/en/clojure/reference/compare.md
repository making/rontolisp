# compare

`(compare x y)`

`clojure.core/compare`: a negative number, zero or a positive number as `x` sorts before,
with or after `y`, the oracle's: `nil` before anything, numbers by value across their kinds
(`(compare 1 1.0)` is `0`), strings, keywords and symbols as Java's `compareTo` (the
difference of the first characters that differ, a name without a namespace first),
characters by their difference, `false` before `true`, vectors by length and then member
by member. Two values of different kinds, or of a kind with no order (a list, a map, a
set), signal. It is the order of `sorted-map`/`sorted-set`, and `sort` takes it as a
comparator. Strings compare by code point, where the oracle compares UTF-16 units (they
differ only past U+FFFF). As a value a two-argument function.

```clojure
(println (compare 1 2) (compare "a" "c") (compare [1 2] [1 2 3])) ; -1 -2 -1
(println (sort #(compare %2 %1) [3 1 2]))                         ; (3 2 1)
```
