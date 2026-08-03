# delete-duplicates

`(delete-duplicates sequence &key test key from-end)`

Returns the sequence with duplicate elements removed — `remove-duplicates`' would-be-destructive twin, sharing its rendering (the standard requires callers to use the RESULT, so the non-destructive scan is conforming, like `sort` via `stable-sort`). By default the last occurrence of each element survives; `:from-end t` keeps the FIRST occurrence instead. The comparison is `eql` by default; `:test` takes a comparison function designator and `:key` a selector applied to both sides. `:from-end` must be a literal `t` or `nil`.

```lisp
(delete-duplicates '(1 2 1 3 2)) ; => (1 3 2)
```

```lisp
(delete-duplicates '(1 2 1 3 2) :from-end t) ; => (1 2 3)
```

```lisp
(delete-duplicates '((1 . :a) (1 . :b) (2 . :c)) :key #'car :from-end t) ; => ((1 . :A) (2 . :C))
```
