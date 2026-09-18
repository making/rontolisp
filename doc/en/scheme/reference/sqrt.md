# sqrt

`(sqrt z)`

Returns the square root of `z`. An exact argument whose root is exact gives an exact result (`(sqrt 16)` is `4`, `(sqrt 1/4)` is `1/2`); otherwise the result is a flonum. There are no complex numbers: the square root of a negative number is refused with an error naming `sqrt`.

```scheme
(sqrt 16) ; => 4
(sqrt 1/4) ; => 1/2
(sqrt 2) ; => 1.4142135623730951
```
