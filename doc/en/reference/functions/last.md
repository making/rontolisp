# last

`(last list &optional n)`

Returns the last cons cell of `list` -- the one-element list containing the final element. For an empty list it returns `nil`. With the optional count `n` it returns the last `n` conses instead: an `n` larger than the list yields the whole list, and `n` of 0 yields the terminating atom (`nil` for a proper list, the dotted tail otherwise).

```lisp
(last '(1 2 3)) ; => (3)
(last '(1 2 3) 2) ; => (2 3)
(last '(1 2 3) 0) ; => NIL
(last '(1 2 3) 5) ; => (1 2 3)
```
