# delete-duplicates

`(delete-duplicates sequence &key test test-not key start end from-end)`

Returns the sequence with duplicate elements removed — `remove-duplicates`' would-be-destructive twin, sharing its rendering (the standard requires callers to use the RESULT, so the non-destructive scan is conforming, like `sort` via `stable-sort`). By default the last occurrence of each element survives; `:from-end t` keeps the FIRST occurrence instead. The comparison is `eql` by default; `:test` takes a comparison function designator, `:test-not` one that matches where it is FALSE, and `:key` a selector applied to both sides. `:start` and `:end` bound the part of the sequence that is CONSIDERED: an element outside that window is kept as it is and is never compared. A window outside the sequence -- a negative or non-integer bound, one past its length, or a start past its end -- signals a `type-error` before anything is compared; a nil `:end` means the length.

```lisp
(delete-duplicates '(1 2 1 3 2)) ; => (1 3 2)
```

```lisp
(delete-duplicates '(1 2 1 3 2) :from-end t) ; => (1 2 3)
```

```lisp
(delete-duplicates '((1 . :a) (1 . :b) (2 . :c)) :key #'car :from-end t) ; => ((1 . :A) (2 . :C))
```

```lisp
(delete-duplicates '(0 1 2 3 1 2 3 9) :start 2 :end 6 :from-end t) ; => (0 1 2 3 1 3 9)
```
