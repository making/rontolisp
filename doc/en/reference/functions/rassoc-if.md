# rassoc-if

`(rassoc-if predicate alist)`

Searches an association list and returns the first pair whose cdr satisfies `predicate`, or `nil` if none does. It is the mirror of `assoc-if`, which tests each pair's car, and the predicate form of `rassoc`. Each pair is tested with `(funcall predicate (cdr pair))`; non-cons elements of the list are skipped. The returned pair shares structure with the alist.

```lisp
(rassoc-if #'oddp '((a . 2) (b . 3))) ; => (B . 3)
```

```lisp
(rassoc-if #'consp '((1 . 2) (3 4 . 5))) ; => (3 4 . 5)
```
