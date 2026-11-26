# let*

`(let* ((variable init)...) body...)`

Like `let`, but binds the variables one after another, so each `init` sees the variables bound before it.

```scheme
(let* ((a 1) (b (+ a 1))) (list a b)) ; => (1 2)
(let ((x 1)) (let* ((x (+ x 1)) (y (* x 10))) (list x y))) ; => (2 20)
```
