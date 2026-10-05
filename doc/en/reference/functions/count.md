# count

`(count item sequence &key test key start end from-end)`

Returns the number of elements in `sequence` that match `item`. The comparison is `eql` by default; the optional `:test` keyword takes a function designator to use a different comparison, and the optional `:key` keyword takes a selector function applied to each element before the comparison. The sequence may be a list or a string (whose elements are characters). Use `count-if` to count by a predicate. `:start`/`:end` bound the scanned subsequence and `:from-end` reverses the order the elements are visited -- that cannot change a count, but a side-effecting `:key` or `:test` sees the reversed order. A bound outside the sequence -- negative, not an integer, past its length, or a start past its end -- signals a `type-error` before any element is examined; a nil `:end` means the length.

```lisp
(count 2 '(1 2 3 2 2)) ; => 3
```

```lisp
(count #\a "banana") ; => 3
```

```lisp
(count "a" '("a" "b" "a") :test #'string=) ; => 2
```

```lisp
(count 1 '(1 1 2 1) :start 1) ; => 2
```
