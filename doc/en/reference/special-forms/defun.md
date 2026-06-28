# defun

`(defun name (params...) body...)`

Defines a function named `name` in the function namespace, with the given parameter list and body, and returns the name symbol. The `body` is not evaluated at definition time; it runs on each call, returning the value of the last body form. Per Lisp-2 the definition lives in the function namespace, so the name is reachable in call position (and via `#'name`) without colliding with any like-named variable.

```lisp
(defun sq (x) (* x x)) ; => sq
```

```lisp
(defun sq (x) (* x x))
(sq 6) ; => 36
```
