# let

`(let ((var init)...) body...)`

Establishes local variable bindings: each `init` is evaluated (in the surrounding scope, so the bindings are parallel, not sequential) and bound to its `var` for the duration of `body`. The `body` forms are evaluated in order and the value of the last one is returned; with an empty body the result is `nil`. The bindings are variable bindings only -- per Lisp-2 they do not shadow the function namespace, so a `let`-bound `car` does not affect calls to the function `car`.

If a `var` names a variable that was proclaimed **special** (by [`defvar`](defvar.md)/[`defparameter`](defparameter.md) or `(declaim (special ...))`), that binding is **dynamic**: it is visible to any function called during `body`, not just lexically nested code, and is restored when `body` exits. Ordinary (non-special) names are bound lexically as usual. See also [`progv`](progv.md) for a runtime-computed list of special bindings.

```lisp
(let ((x 2) (y 3)) (+ x y)) ; => 5
```

```lisp
(defvar *depth* 0)
(let ((*depth* (+ *depth* 1))) *depth*) ; => 1
```
