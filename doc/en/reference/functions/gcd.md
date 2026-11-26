# gcd

`(gcd &rest integers)`

Returns the greatest common divisor of its integer arguments. It is variadic; with no arguments it returns `0`, and with one argument it returns that integer's absolute value. The result is always a non-negative integer.

```lisp
(gcd 12 18) ; => 6
```

```lisp
(gcd 24 36 60) ; => 12
```
