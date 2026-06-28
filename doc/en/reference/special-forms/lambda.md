# lambda

`(lambda (params...) body...)`

Creates an anonymous function with the given parameter list and body, closing over the lexical variables in scope. The `body` is not evaluated when the `lambda` is created; it runs each time the resulting function is called, returning the value of the last body form. The function value can be called with `funcall`/`apply` or passed to higher-order functions like `mapcar`. In call position a `lambda` form may also be used directly, e.g. `((lambda (x) x) 1)`.

```lisp
(funcall (lambda (x) (* x x)) 5) ; => 25
```
