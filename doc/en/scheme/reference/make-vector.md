# make-vector

`(make-vector k)` `(make-vector k fill)`

Returns a new vector of length `k`, every element `fill`. Without `fill` every element is `0` (R7RS leaves the contents unspecified).

```scheme
(make-vector 3 'x) ; => #(x x x)
(make-vector 2) ; => #(0 0)
```
