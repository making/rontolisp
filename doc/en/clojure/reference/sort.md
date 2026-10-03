# sort

`(sort coll)` / `(sort comp coll)`

Answers the seq view copied and sorted as a list. Without a comparator numbers
sort with `<`, strings with `string<`, characters with `char<` and keywords by
spelling; anything else (or mixed kinds) signals instead of answering wrongly.
With one, its answer is read like the oracle's: a number puts the first argument
first when its integer part is negative (`compare`, `(- a b)`), anything else when
it is truthy (`<`, `>`). As a value a one- or two-argument lambda.

```clojure
(println (sort [3 1 2])) ; (1 2 3)
(println (sort > [1 3 2])) ; (3 2 1)
(println (sort compare ["b" "a"])) ; (a b)
```
