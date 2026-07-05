# write-to-string

`(write-to-string object)`

Returns `object`'s readable (`prin1`) printed representation as a string -- an alias for [prin1-to-string](prin1-to-string.md). The full Common Lisp `write` keyword arguments (`:escape`, `:base`, ...) are not supported.

```lisp
(write-to-string '(a b 3)) ; => "(a b 3)"
```
