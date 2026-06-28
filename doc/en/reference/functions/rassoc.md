# rassoc

`(rassoc value alist)`

Searches an association list and returns the first pair whose cdr is `eql` to `value`, or `nil` if none matches. It is the mirror of `assoc`, which searches by car. The returned pair shares structure with the alist.

```lisp
(rassoc 2 (list (cons 'a 1) (cons 'b 2))) ; => (b . 2)
```
