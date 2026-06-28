# cons

`(cons object1 object2)`

Allocates and returns a fresh cons cell whose car is `object1` and whose cdr is `object2`. When the cdr is a list the result is a longer list; otherwise it is a dotted pair. This is the fundamental list-building primitive.

```lisp
(cons 1 '(2 3)) ; => (1 2 3)
```

```lisp
(cons 1 2) ; => (1 . 2)
```
