# define-symbol-macro

`(define-symbol-macro name expansion)`

Defines a global symbol macro: from here on, a reference to `name` in a value position evaluates `expansion` in its place, and a `setq`/`setf` of `name` assigns through `expansion` as a `setf` place. It is the top-level sibling of [`symbol-macrolet`](../macros/symbol-macrolet.md) and follows the same substitution rules -- quoted data, function-namespace positions (`#'name`, call heads), `case`-family keys, `go` tags and `block` names are never substituted, and an inner binding of the same name (`let`, a `lambda` parameter, `dolist`, ...) shadows it in its scope. `name` is not a variable: nothing binds it, and the `expansion` form is re-evaluated at every reference. Returns the name.

The definition must be a **top-level** form (a `progn` or `eval-when` wrapping it is fine, which is the shape `cffi:defcvar` expands into) and `name` must be a literal symbol; the compilers resolve the definition when they read the program, so a reference in a form that precedes it does not see it.

```lisp
(defvar *buf* (make-array 3 :initial-element 0))
(define-symbol-macro slot0 (aref *buf* 0))
(setf slot0 42)
(incf slot0)
(list slot0 *buf*) ; => (43 #(43 0 0))
```
