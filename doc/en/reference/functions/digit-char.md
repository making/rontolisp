# digit-char

`(digit-char weight &optional radix)`

Returns the character denoting `weight` in `radix` (10 by default), in upper case, or `nil` when the weight is not a non-negative integer below the radix. It is the inverse of [`digit-char-p`](digit-char-p.md).

```lisp
(list (digit-char 7) (digit-char 11 16) (digit-char 12)) ; => (#\7 #\B NIL)
```
