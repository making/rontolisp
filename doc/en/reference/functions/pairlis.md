# pairlis

`(pairlis keys data &optional alist)`

Pairs up a list of keys and a list of values into an association list, preserving key order, and appends the optional existing `alist` as the tail. Pairing stops at the end of the shorter list.

```lisp
(pairlis '(a b) '(1 2)) ; => ((a . 1) (b . 2))
```

```lisp
(pairlis '(a b) '(1 2) '((c . 3))) ; => ((a . 1) (b . 2) (c . 3))
```
