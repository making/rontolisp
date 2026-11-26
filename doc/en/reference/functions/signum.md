# signum

`(signum number)`

Returns `-1`, `0`, or `1` indicating the sign of `number`, preserving its numeric type. An integer or ratio argument yields an integer result, while a float argument yields a float result (e.g. `1.0`, `0.0`, `-1.0`). A complex argument yields its unit vector `z/|z|` in floats -- `#C(0.6 0.8)` for `#c(3 4)` -- while a zero answers the canonicalization of its own parts (`0` for exact parts, `#C(0.0 0.0)` for float parts).

```lisp
(signum -5) ; => -1
```

```lisp
(signum 3.5) ; => 1.0
```

```lisp
(signum #c(3 4)) ; => #C(0.6 0.8)
```
