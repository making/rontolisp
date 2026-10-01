# sort

`(sort coll)` / `(sort comp coll)`

Answers the seq view copied and sorted as a list. Without a comparator numbers
sort with `<`, strings with `string<`, characters with `char<` and keywords by
spelling; anything else (or mixed kinds) signals instead of answering wrongly.
With one, it runs on truthiness through the same null-or-false test every
conditional uses. As a value a one- or two-argument lambda.

```clojure
(println (sort [3 1 2])) ; (1 2 3)
(println (sort > [1 3 2])) ; (3 2 1)
```
