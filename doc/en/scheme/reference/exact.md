# exact

`(exact z)`

Returns the exact number equal to `z`. A flonum converts to the exact value of its binary representation, so `(exact 2.5)` is `5/2` but `(exact 0.1)` is `3602879701896397/36028797018963968`, not `1/10`. An infinity or NaN signals an error.

```scheme
(exact 2.5) ; => 5/2
(exact 2.0) ; => 2
(exact 0.1) ; => 3602879701896397/36028797018963968
```
