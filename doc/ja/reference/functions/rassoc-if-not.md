# rassoc-if-not

`(rassoc-if-not predicate alist &key key)`

述語が cdr に対して偽を返した最初のペア。なければ nil を返します。[`assoc-if-not`](assoc-if-not.md) の鏡像です。規格では非推奨です。

```lisp
(rassoc-if-not #'numberp '((a . 1) (b . c))) ; => (B . C)
```

```lisp
(rassoc-if-not #'oddp '((a . 1) (b . 2)) :key #'1+) ; => (A . 1)
```
