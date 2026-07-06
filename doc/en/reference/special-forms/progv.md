# progv

`(progv symbols values body...)`

Evaluates `symbols` and `values` (each a list), then dynamically binds each symbol to the corresponding value for the duration of `body`, restoring the previous values on exit. When `values` is shorter than `symbols`, the extra symbols are bound to `nil`. Unlike [`let`](let.md), the symbols are computed at runtime and need not have been proclaimed special. Returns the value of the last body form.

`progv` is supported on the **interpreter only**; the JVM and WASM compilers reject it (the bound symbols are not known at compile time, so their storage cannot be named).

```lisp
(progv '(a b) '(1 2) (list (symbol-value 'a) (symbol-value 'b))) ; => (1 2)
```
