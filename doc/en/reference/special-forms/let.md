# let

`(let ((var init)...) body...)`

Establishes local variable bindings: each `init` is evaluated (in the surrounding scope, so the bindings are parallel, not sequential) and bound to its `var` for the duration of `body`. The `body` forms are evaluated in order and the value of the last one is returned; with an empty body the result is `nil`. The bindings are variable bindings only -- per Lisp-2 they do not shadow the function namespace, so a `let`-bound `car` does not affect calls to the function `car`.

If a `var` names a variable that was proclaimed **special** (by [`defvar`](defvar.md)/[`defparameter`](defparameter.md) or `(declaim (special ...))`), or a `(declare (special var))` at the head of `body` names it, that binding is **dynamic**: it is visible to any function called during `body`, not just lexically nested code, and is restored when `body` exits. A reference to a special variable reads the binding in effect when it runs, in a closure as anywhere else, so a closure built in `body` and called after the `let` has exited reads the global value. Ordinary (non-special) names are bound lexically as usual, and so is a name that only a local declaration elsewhere makes special: such a declaration covers its own binding and the references in its body ([`declare`](../macros/declare.md)). See also [`progv`](progv.md) for a runtime-computed list of special bindings.

```lisp
(let ((x 2) (y 3)) (+ x y)) ; => 5
```

```lisp
(defvar *depth* 0)
(let ((*depth* (+ *depth* 1))) *depth*) ; => 1
```

```lisp
(defvar *mode* :global)
(defun mode-reader () (let ((*mode* :bound)) (lambda () *mode*)))
(funcall (mode-reader)) ; => :GLOBAL
```
