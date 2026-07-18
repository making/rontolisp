# scale-float

`(scale-float float integer)`

Returns `float × 2^integer` with exact IEEE 754 semantics (including the subnormal range).

Supported on the **interpreter only** for now; the JVM and WASM compilers do not support it yet.

```lisp
(scale-float 1.5 3) ; => 12.0
```
