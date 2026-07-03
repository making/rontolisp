# rassoc

`(rassoc value alist &key test)`

Searches an association list and returns the first pair whose cdr matches `value`, or `nil` if none matches. It is the mirror of `assoc`, which searches by car. The comparison is `eql` by default; the optional `:test` keyword takes a function designator to use a different comparison. The returned pair shares structure with the alist.

```lisp
(rassoc 2 '((a . 1) (b . 2))) ; => (b . 2)
```

```lisp
(rassoc "x" '((a . "w") (b . "x")) :test #'equal) ; => (b . "x")
```
