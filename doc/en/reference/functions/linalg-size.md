# linalg:size

`(linalg:size array)`

Returns the total number of elements in an array -- the product of its dimensions, like `array-total-size`. For the per-dimension sizes, use [`linalg:shape`](linalg-shape.md).

```lisp
(linalg:size #2A((1 2 3) (4 5 6))) ; => 6
```
