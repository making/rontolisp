# last

`(last list)`

Returns the last cons cell of `list` -- the one-element list containing the final element. For an empty list it returns `nil`. Unlike full Common Lisp, rontolisp's `last` takes only a list: the optional count argument (`(last list n)`) is not supported.

```lisp
(last '(1 2 3)) ; => (3)
```
