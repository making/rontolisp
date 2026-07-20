# rassoc

`(rassoc value alist &key test key)`

Searches an association list and returns the first pair whose cdr matches `value`, or `nil` if none matches. It is the mirror of `assoc`, which searches by car. The comparison is `eql` by default; the optional `:test` keyword takes a function designator to use a different comparison, and the optional `:key` keyword takes a selector function applied to each pair's cdr before the comparison. The returned pair shares structure with the alist.

```lisp
(rassoc 2 '((a . 1) (b . 2))) ; => (B . 2)
```

```lisp
(rassoc "x" '((a . "w") (b . "x")) :test #'equal) ; => (B . "x")
```

```lisp
(rassoc 2 '((a . 1) (b . 3)) :key (lambda (v) (- v 1))) ; => (B . 3)
```
