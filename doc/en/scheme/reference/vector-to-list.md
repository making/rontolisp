# vector->list

`(vector->list vector)` `(vector->list vector start)` `(vector->list vector start end)`

Returns a list of the elements of `vector`, or of the part from `start` (inclusive) to `end` (exclusive, default the end of the vector).

```scheme
(vector->list #(1 2 3)) ; => (1 2 3)
(vector->list #(1 2 3 4) 1 3) ; => (2 3)
```
