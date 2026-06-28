# count

`(count item list)`

Returns the number of elements in `list` that are `eql` to `item`. Comparison is by `eql` only. Use `count-if` to count by a predicate.

```lisp
(count 2 '(1 2 3 2 2)) ; => 3
```
