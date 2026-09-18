# floor

`(floor x)`

Returns the largest integer not greater than `x`. A flonum argument gives a flonum (`(floor 2.5)` is `2.0`); an exact argument gives an exact integer.

```scheme
(floor 2.5) ; => 2.0
(floor -2.5) ; => -3.0
(floor 7/2) ; => 3
```
