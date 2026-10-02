# make-rectangular

`(make-rectangular real imaginary)`

Returns the complex `real + imaginary*i`. A rational zero imaginary part gives the real itself, so `(make-rectangular 1 0)` is `1`; a float zero stays complex.

```scheme
(make-rectangular 1 2) ; => #C(1 2)
(make-rectangular 1 0) ; => 1
```
