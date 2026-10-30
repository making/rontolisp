# functionp

`(functionp object)`

Returns `t` if `object` is a function value — the result of `(lambda ...)`, `#'name` or `symbol-function` — or a funcallable instance (an instance of a `closer-mop:funcallable-standard-class` class), and `nil` for any other object. In Lisp-2 a bare symbol is never a function, so `(functionp 'car)` is `nil` while `(functionp #'car)` is `t`.

A named function value PRINTS as `#<function NAME>` and an anonymous one (`lambda`, `flet`, `labels`) as `#<lambda>`. NAME is the registered name, uppercased: bare inside its own package, `PKG:` through an export, `PKG::` for an internal. The text is identical on every backend and carries no identity hash or address.

```lisp
(functionp #'car) ; => T
```

```lisp
(functionp (lambda (x) x)) ; => T
```

```lisp
(functionp 'car) ; => NIL
```
