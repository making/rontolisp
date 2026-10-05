# remove-duplicates

`(remove-duplicates sequence &key test test-not key start end from-end)`

Returns a new sequence with duplicate elements removed, keeping the last occurrence of each (so the order of the surviving elements follows their last appearance); `:from-end t` keeps the FIRST occurrence instead. The comparison is `eql` by default; the optional `:test` keyword takes a function designator to use a different comparison, `:test-not` takes one that matches where it is FALSE, and the optional `:key` keyword takes a selector function applied to each element before the comparison. `:start` and `:end` bound the part of the sequence that is CONSIDERED: an element outside that window is kept as it is and is never compared. A window outside the sequence -- a negative or non-integer bound, one past its length, or a start past its end -- signals a `type-error` before anything is compared; a nil `:end` means the length. The sequence may be a list or a string; a string yields a new string. The original sequence is not modified. See also [`delete-duplicates`](delete-duplicates.md), which shares this rendering.

```lisp
(remove-duplicates '(1 2 1 3)) ; => (2 1 3)
```

```lisp
(remove-duplicates "banana") ; => "bna"
```

```lisp
(remove-duplicates '("a" "b" "a" "c") :test #'string=) ; => ("b" "a" "c")
```

```lisp
(remove-duplicates '(0 1 2 3 1 2 3 9) :start 2 :end 6) ; => (0 1 3 1 2 3 9)
```
