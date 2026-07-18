# psetf

`(psetf place1 e1 place2 e2 ...)`

`psetq` generalized to `setf` places: every place subform and every right-hand side expression is evaluated into a temporary first, and only then are the places assigned, so a later place that reads a variable assigned by an earlier pair still sees the old value. `psetf` always returns nil.

```lisp
(let ((a 1) (b 2)) (psetf a b b a) (list a b)) ; => (2 1)
```

```lisp
(let* ((tail (list 2))
       (last-cdr tail)
       (fresh (list 3)))
  (psetf last-cdr fresh
         (cdr last-cdr) fresh)
  tail) ; => (2 3)
```
