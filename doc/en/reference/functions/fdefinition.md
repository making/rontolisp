# fdefinition

`(fdefinition symbol)`

The function value of a symbol, like [`symbol-function`](symbol-function.md) (setf-function names are not supported).

Supported on the **interpreter only** for now; the JVM and WASM compilers do not support it yet.

```lisp
(funcall (fdefinition 'car) '(1 2 3)) ; => 1
```
