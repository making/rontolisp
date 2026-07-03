# array-dimensions

`(array-dimensions array)`

Returns a list of the sizes of each dimension of `array`. A rank-1 vector yields a one-element list and a rank-2 array yields a two-element list (only ranks 1 and 2 are supported). See [`array-dimension`](array-dimension.md) for the size of a single axis and [`array-rank`](array-rank.md) for the number of dimensions.

```lisp
(array-dimensions (make-array '(2 3))) ; => (2 3)
(array-dimensions (vector 1 2)) ; => (2)
```
