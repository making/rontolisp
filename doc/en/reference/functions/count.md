# count

`(count item sequence)`

Returns the number of elements in `sequence` that are `eql` to `item`. The sequence may be a list or a string (whose elements are characters). Comparison is by `eql` only. Use `count-if` to count by a predicate.

```lisp
(count 2 '(1 2 3 2 2)) ; => 3
```

```lisp
(count #\a "banana") ; => 3
```
