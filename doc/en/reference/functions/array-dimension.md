# array-dimension

`(array-dimension array axis-number)`

Returns the size of the dimension of `array` at the 0-based `axis-number`. For a rank-1 vector the only valid axis is 0; for a rank-n array the axes are 0 to n-1. Use [`array-dimensions`](array-dimensions.md) to get all sizes at once.

A string is a rank-1 array of characters, so axis 0 is its capacity.

```lisp
(array-dimension (make-array '(2 3)) 0) ; => 2
(array-dimension (make-array '(2 3)) 1) ; => 3
(array-dimension "abc" 0) ; => 3
```
