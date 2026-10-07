# fdefinition

`(fdefinition function-name)`

The function value of a function name: a symbol, like [`symbol-function`](symbol-function.md), or a `(setf name)` list naming the function `(defun (setf name) ...)` defines. An undefined name signals `undefined-function`, whose `cell-error-name` is the name, list included.

A quoted symbol literal (`(fdefinition 'car)`) resolves at compile time in the compilers; a runtime-computed symbol resolves late through the compiled name registry and answers the same function value -- like [`symbol-function`](symbol-function.md), with no deviations. A quoted `(setf name)` list is `#'(setf name)` on every backend; the compiled backends do not take a `(setf name)` list built at run time, which only the interpreter accepts.

```lisp
(funcall (fdefinition 'car) '(1 2 3)) ; => 1
```

```lisp
(defun (setf fd-first) (value list) (setf (car list) value))
(let ((l (list 1 2)))
  (funcall (fdefinition '(setf fd-first)) 9 l)
  l) ; => (9 2)
```

`fdefinition` is the same `setf` place as [`symbol-function`](symbol-function.md): `(setf (fdefinition 'name) fn)` installs `fn` as the symbol's global function definition, and `(setf (fdefinition '(setf name)) fn)` installs the `(setf name)` function.
