# linalg:mean

`(linalg:mean array)`

Returns the arithmetic mean of every element: the [`linalg:sum`](linalg-sum.md) divided by the [`linalg:size`](linalg-size.md). Integer inputs give an exact rational result rather than a float.

```lisp
(linalg:mean (linalg:from-list '(1 2 3 4))) ; => 5/2
```
