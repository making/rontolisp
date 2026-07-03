# array-dimension

`(array-dimension array axis-number)`

Returns the size of the dimension of `array` at the 0-based `axis-number`. For a rank-1 vector the only valid axis is 0; for a rank-2 array the axes are 0 and 1. Use [`array-dimensions`](array-dimensions.md) to get all sizes at once.

```lisp
(array-dimension (make-array '(2 3)) 0) ; => 2
(array-dimension (make-array '(2 3)) 1) ; => 3
```
