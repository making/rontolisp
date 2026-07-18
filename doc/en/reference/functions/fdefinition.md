# fdefinition

`(fdefinition symbol)`

The function value of a symbol, like [`symbol-function`](symbol-function.md) (setf-function names are not supported).

In the compilers the argument must be a quoted symbol literal (`(fdefinition 'car)`), since the binding is resolved at compile time -- like [`symbol-function`](symbol-function.md); a runtime-computed symbol works on the interpreter only.

```lisp
(funcall (fdefinition 'car) '(1 2 3)) ; => 1
```
