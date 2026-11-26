# eql

`(eql x y)`

Returns `t` for two numbers of the same type and value, for two equal characters, and otherwise for the same object. Here it is the same predicate as [`eq`](eq.md), which CL allows to compare numbers by identity; `eql` is the portable spelling of the numeric comparison. Numbers of different types are not `eql` (`(eql 3 3.0)` is `nil`). It does not descend into cons cells or compare string contents; use `equal` for structural comparison. Works in all three backends.

```lisp
(eql 1.5 1.5) ; => T
```

```lisp
(eql 3 3.0) ; => NIL
```
