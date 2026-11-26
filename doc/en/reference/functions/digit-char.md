# digit-char

`(digit-char weight &optional radix)`

Returns the character denoting `weight` in `radix` (10 by default), in upper case, or `nil` when the weight is not below the radix. A `weight` that is not a non-negative integer signals a `type-error` (type `UNSIGNED-BYTE`), and so does a `radix` that is not an integer from 2 to 36 (type `(INTEGER 2 36)`), the weight checked first. It is the inverse of [`digit-char-p`](digit-char-p.md).

```lisp
(list (digit-char 7) (digit-char 11 16) (digit-char 12)) ; => (#\7 #\B NIL)
```
