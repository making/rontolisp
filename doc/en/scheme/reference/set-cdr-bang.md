# set-cdr!

`(set-cdr! pair obj)`

Stores `obj` in the cdr of `pair`. Called for its effect, like `set-car!`.

```scheme
(let ((p (list 1 2))) (set-cdr! p '(3 4)) p) ; => (1 3 4)
(define p (list 1 2))
(set-cdr! p '(3 4))
p ; => (1 3 4)
```
