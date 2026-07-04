# linalg:amax

`(linalg:amax array)`

Returns the largest element of a vector or matrix. An empty array signals an error. For the *index* of the largest element of a vector, use [`linalg:argmax`](linalg-argmax.md); the counterpart for the smallest element is [`linalg:amin`](linalg-amin.md).

```lisp
(linalg:amax #2A((1 9) (3 4))) ; => 9
```
