# lambda

`(lambda (params...) body...)`

Creates an anonymous function with the given parameter list and body, closing over the lexical variables in scope. The `body` is not evaluated when the `lambda` is created; it runs each time the resulting function is called, returning the value of the last body form. The function value can be called with `funcall`/`apply` or passed to higher-order functions like `mapcar`. In call position a `lambda` form may also be used directly, e.g. `((lambda (x) x) 1)`.

```lisp
(funcall (lambda (x) (* x x)) 5) ; => 25
```

The parameter list supports the same lambda-list keywords as [`defun`](defun.md) (`&optional`, `&rest`, `&key`, `&allow-other-keys`, `&aux`):

```lisp
(funcall (lambda (&rest xs) xs) 1 2 3) ; => (1 2 3)
```

```lisp
(mapcar (lambda (x &optional (y 100)) (+ x y)) (list 1 2 3)) ; => (101 102 103)
```
