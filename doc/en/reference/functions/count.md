# count

`(count item sequence &key test key)`

Returns the number of elements in `sequence` that match `item`. The comparison is `eql` by default; the optional `:test` keyword takes a function designator to use a different comparison, and the optional `:key` keyword takes a selector function applied to each element before the comparison. The sequence may be a list or a string (whose elements are characters). Use `count-if` to count by a predicate.

```lisp
(count 2 '(1 2 3 2 2)) ; => 3
```

```lisp
(count #\a "banana") ; => 3
```

```lisp
(count "a" '("a" "b" "a") :test #'string=) ; => 2
```
