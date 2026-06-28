# first

`(first list)`

Returns the first element of a list. It is an exact synonym for `car`, including the `(first nil)` is `nil` behavior, but reads more naturally when the argument is meant as a list rather than a raw cons cell.

```lisp
(first '(10 20 30)) ; => 10
```
