# reverse

`(reverse sequence)`

Returns a new sequence with the elements of `sequence` in reverse order, leaving the original untouched. This is the non-destructive counterpart to `nreverse`. The empty list reverses to `nil`.

```lisp
(reverse '(1 2 3)) ; => (3 2 1)
```
