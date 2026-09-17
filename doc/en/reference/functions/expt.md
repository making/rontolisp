# expt

`(expt base power)`

Returns `base` raised to `power`. With integer arguments the result is an exact integer (`(expt 2 10)` is `1024`), and a negative integer power gives the reciprocal (`(expt 2 -1)` is `1/2`); if either argument is a float -- or the power is a ratio -- the result is a float, and a fractional power (`(expt 2 0.5)`, `(expt 10000.0 0.75)`) works on every backend. The dispatch is on the run-time values, so a power computed at run time behaves like a literal one. A float or fractional power is fdlibm's `pow` on every backend ([Math Function Backends](../../guides/math-backends.md)), so the digits agree everywhere. A NEGATIVE base to a non-integer power leaves the real line and answers the plane instead of `NaN`: `(expt -8 1/3)` is `#C(1.0000000000000002 1.7320508075688772)`, the modulus `|base|^power` turned through `power * pi` radians. An integer power -- including an integer-valued float like `2.0` -- stays real whatever the base's sign, so `(expt -8.0 2.0)` is `64.0`. Works in all three backends.

```lisp
(expt 2 10) ; => 1024
```

```lisp
(expt 2.0 3) ; => 8.0
```

```lisp
(expt 4 1/2) ; => 2.0
```
