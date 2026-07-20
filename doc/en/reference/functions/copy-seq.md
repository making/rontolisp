# copy-seq

`(copy-seq sequence)`

Returns a fresh copy of `sequence` — a list or a string — equivalent to `(subseq sequence 0)`. The copy shares no cons cells with the original, so destructive operations on one do not affect the other.

```lisp
(let ((original '(1 2 3)))
  (eq (copy-seq original) original)) ; => NIL
```

```lisp
(copy-seq "xyz") ; => "xyz"
```
