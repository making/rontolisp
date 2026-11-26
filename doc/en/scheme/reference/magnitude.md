# magnitude

`(magnitude z)`

Returns the absolute value of `z`: `sqrt(real-part^2 + imag-part^2)` for a complex, the ordinary absolute value for a real.

```scheme
(magnitude #C(3 4)) ; => 5.0
(magnitude 3) ; => 3
```
