# abs

`(abs number)`

Returns the absolute value of `number`, preserving its numeric type: an integer yields an integer, a float yields a float, and a ratio yields a ratio. A complex argument yields its float modulus, a real even for exact parts.

```lisp
(abs -5) ; => 5
```

```lisp
(abs -3.14) ; => 3.14
```

```lisp
(abs #c(3 4)) ; => 5.0
```
