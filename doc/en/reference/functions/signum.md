# signum

`(signum number)`

Returns `-1`, `0`, or `1` indicating the sign of `number`, preserving its numeric type. An integer or ratio argument yields an integer result, while a float argument yields a float result (e.g. `1.0`, `0.0`, `-1.0`).

```lisp
(signum -5) ; => -1
```

```lisp
(signum 3.5) ; => 1.0
```
