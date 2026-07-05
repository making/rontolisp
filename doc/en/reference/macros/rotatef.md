# rotatef

`(rotatef place...)`

Rotates the values of its [`setf`](setf.md)-able places to the left: the first place receives the old value of the second, and so on, and the last place receives the old value of the first. Returns nil. Every place is read into a temporary before any is written, so a two-place `rotatef` swaps.

```lisp
(let ((x 1) (y 2))
  (rotatef x y)
  (list x y)) ; => (2 1)
```

```lisp
(let ((a 1) (b 2) (c 3))
  (rotatef a b c)
  (list a b c)) ; => (2 3 1)
```

```lisp
(let ((x (cons 1 2)))
  (rotatef (car x) (cdr x))
  x) ; => (2 . 1)
```
