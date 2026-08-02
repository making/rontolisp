# fdefinition

`(fdefinition symbol)`

The function value of a symbol, like [`symbol-function`](symbol-function.md) (setf-function names are not supported).

A quoted symbol literal (`(fdefinition 'car)`) resolves at compile time in the compilers; a runtime-computed symbol resolves late through the compiled name registry when the result is called, with the same deviations as [`symbol-function`](symbol-function.md).

```lisp
(funcall (fdefinition 'car) '(1 2 3)) ; => 1
```

`fdefinition` is the same `setf` place as [`symbol-function`](symbol-function.md): `(setf (fdefinition 'name) fn)` installs `fn` as the symbol's global function definition.
