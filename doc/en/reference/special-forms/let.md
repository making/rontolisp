# let

`(let ((var init)...) body...)`

Establishes local variable bindings: each `init` is evaluated (in the surrounding scope, so the bindings are parallel, not sequential) and bound to its `var` for the duration of `body`. The `body` forms are evaluated in order and the value of the last one is returned; with an empty body the result is `nil`. The bindings are variable bindings only -- per Lisp-2 they do not shadow the function namespace, so a `let`-bound `car` does not affect calls to the function `car`.

```lisp
(let ((x 2) (y 3)) (+ x y)) ; => 5
```
