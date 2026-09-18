# lcm

`(lcm n ...)`

Returns the least common multiple of its arguments, always non-negative; `(lcm)` is `1`. The arguments must be exact integers, as for `gcd`.

```scheme
(lcm 4 6) ; => 12
(lcm -3 4) ; => 12
```
