# gcd

`(gcd n ...)`

Returns the greatest common divisor of its arguments, always non-negative; `(gcd)` is `0`. The arguments must be exact integers: an inexact integer such as `2.0` signals an error.

```scheme
(gcd 12 18) ; => 6
(gcd -12 18) ; => 6
(gcd) ; => 0
```
