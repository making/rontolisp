# lcm

`(lcm n ...)`

Returns the least common multiple of its arguments, always non-negative; `(lcm)` is `1`. An inexact integer makes the result inexact and a non-integer signals an error, as for `gcd`.

```scheme
(lcm 4 6) ; => 12
(lcm -3 4) ; => 12
(lcm 2.0 3) ; => 6.0
```
