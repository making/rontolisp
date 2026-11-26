# gcd

`(gcd n ...)`

Returns the greatest common divisor of its arguments, always non-negative; `(gcd)` is `0`. An inexact integer such as `2.0` is accepted and makes the result inexact; an argument that is not an integer signals an error.

```scheme
(gcd 12 18) ; => 6
(gcd -12 18) ; => 6
(gcd) ; => 0
(gcd 2.0 4) ; => 2.0
```
