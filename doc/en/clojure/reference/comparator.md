# comparator

`(comparator pred)`

`clojure.core/comparator`: a two-argument function answering `-1` when `(pred a b)` is
truthy, else `1` when `(pred b a)` is, else `0`. It turns a predicate such as `<` or `>`
into the comparator `sort`, `sort-by`, `sorted-map-by` and `sorted-set-by` take. As a value
a one-argument function.

```clojure
(prn (sort (comparator >) [1 3 2]))           ; (3 2 1)
(prn ((comparator <) 1 2) ((comparator <) 1 1)) ; -1 0
```
