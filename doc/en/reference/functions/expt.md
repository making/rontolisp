# expt

`(expt base power)`

Returns `base` raised to `power`. With integer arguments the result is an exact integer (`(expt 2 10)` is `1024`), and a negative integer power gives the reciprocal (`(expt 2 -1)` is `1/2`); if either argument is a float -- or the power is a ratio -- the result is a float, and a fractional power (`(expt 2 0.5)`, `(expt 10000.0 0.75)`) works on every backend. The dispatch is on the run-time values, so a power computed at run time behaves like a literal one. On the WASM backends a fractional power is `exp(y * log(x))` over the software `exp`/`log` ([Math Function Backends](../../guides/math-backends.md)), so its low-order digits differ from `Math.pow`'s. Works in all three backends.

```lisp
(expt 2 10) ; => 1024
```

```lisp
(expt 2.0 3) ; => 8.0
```

```lisp
(expt 4 1/2) ; => 2.0
```
