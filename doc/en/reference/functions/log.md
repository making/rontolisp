# log

`(log number)`

Returns the natural logarithm (base e) of `number` as a float. Only the one-argument form is supported -- there is no `(log number base)` form for an arbitrary base. The interpreter and JVM backends compute it with `Math.log`; the WASM backend uses a software approximation (exponent extraction plus a polynomial series), so its result may differ slightly in the least significant digits. The IEEE edges match everywhere: `(log 0.0)` is `-Infinity`, a negative argument gives `NaN`.

```lisp
(log 1) ; => 0.0
```
