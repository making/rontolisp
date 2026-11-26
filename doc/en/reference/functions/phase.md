# phase

`(phase number)`

Returns the angle of `number` in the complex plane as a float: `atan2` of the imaginary and real parts for a complex, `0.0` for a non-negative real and pi for a negative one. Works on all four backends, with fdlibm's `atan2` on each, so the digits agree everywhere.

```lisp
(phase 5) ; => 0.0
```

```lisp
(phase -5) ; => 3.141592653589793
```
