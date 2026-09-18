# number->string

`(number->string z)` `(number->string z radix)`

Returns the external representation of `z` as a string. `radix` (2, 8, 10 or 16, default 10) applies to exact numbers, a ratio's numerator and denominator alike; a flonum is written in decimal. A flonum is written positionally below `1e21` and with an exponent from there (and below `1e-6`).

```scheme
(number->string 255 16) ; => "ff"
(number->string 10 2) ; => "1010"
(number->string 1/3 2) ; => "1/11"
(number->string 3.5) ; => "3.5"
```
