# asinh acosh atanh

`(asinh number)` `(acosh number)` `(atanh number)`

The inverse hyperbolic functions. `asinh` accepts every real number; `acosh` has the real domain `[1, +inf)` and `atanh` the interval `(-1, 1)` -- a real argument outside that domain crosses into the complex plane the way `sqrt` roots a negative, so `(acosh 0)` has imaginary part pi/2 and `(atanh 2)` real part ln 3 / 2 and imaginary part pi/2. A complex argument answers the plane: `acosh` takes the ANSI form `2·log(sqrt((z+1)/2) + sqrt((z-1)/2))` and `atanh` the difference of the principal logarithms `(log(1+z) - log(1-z)) / 2`. `java.lang.Math` has no inverse hyperbolic, so all three are one hand-rolled formula each over fdlibm's `log1p`, `log` and `hypot`, evaluated the same way on every backend, so the bits agree everywhere. `(asinh 0)`, `(acosh 1)` and `(atanh 0)` are exactly `0.0` on every backend.

```lisp
(asinh 0) ; => 0.0
(acosh 1) ; => 0.0
(atanh 0) ; => 0.0
```
