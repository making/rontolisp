# letrec*

`(letrec* ((variable init)...) body...)`

Like `letrec`, but evaluates the `init`s left to right, so an `init` may use the value of a variable bound before it. Internal definitions in a body behave like `letrec*`.

```scheme
(letrec* ((a 1) (b (+ a 1))) (list a b)) ; => (1 2)
(let ((x 10)) (letrec* ((a x) (b (* a 2))) b)) ; => 20
```
