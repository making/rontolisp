# caar cddddr

`(cadr list)`, `(caddr list)`, ... -- every `c{a,d}+r` name from two to four levels deep.

Each composite accessor applies a sequence of `car`/`cdr` operations read right to left: `cadr` is `(car (cdr x))`, `caddr` is `(car (cdr (cdr x)))`, `cddr` is `(cdr (cdr x))`, and so on through all 2-, 3-, and 4-level combinations up to `cddddr`. Like `car`/`cdr`, applying one to `nil` along the way yields `nil` instead of erroring.

```lisp
(caddr '(1 2 3 4)) ; => 3
```

```lisp
(cddr '(1 2 3 4)) ; => (3 4)
```
