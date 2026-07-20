# functionp

`(functionp object)`

Returns `t` if `object` is a function value — the result of `(lambda ...)`, `#'name` or `symbol-function` — and `nil` for any other object. In Lisp-2 a bare symbol is never a function, so `(functionp 'car)` is `nil` while `(functionp #'car)` is `t`.

```lisp
(functionp #'car) ; => T
```

```lisp
(functionp (lambda (x) x)) ; => T
```

```lisp
(functionp 'car) ; => NIL
```
