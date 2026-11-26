# rassoc-if-not

`(rassoc-if-not predicate alist &key key)`

The first pair of `alist` whose cdr the predicate REJECTS, or nil -- the mirror of [`assoc-if-not`](assoc-if-not.md). Deprecated by the standard.

```lisp
(rassoc-if-not #'numberp '((a . 1) (b . c))) ; => (B . C)
```

```lisp
(rassoc-if-not #'oddp '((a . 1) (b . 2)) :key #'1+) ; => (A . 1)
```
