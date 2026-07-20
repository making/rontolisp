# eql

`(eql x y)`

Like `eq`, but additionally returns `t` for two numbers of the same type and value -- so equal floats and equal ratios compare `eql` even though they are not `eq`. Numbers of different types are not `eql` (`(eql 3 3.0)` is `nil`). It does not descend into cons cells; use `equal` for structural comparison. Works in all three backends.

```lisp
(eql 1.5 1.5) ; => T
```

```lisp
(eql 3 3.0) ; => NIL
```
