# equalp

`(equalp x y)`

Like [`equal`](equal.md), but strings and characters are compared case-insensitively and numbers by numeric value (`=`). Cons cells are compared recursively. Lite: arrays, hash tables and structures fall back to `eql` rather than being compared element by element.

```lisp
(equalp "ABC" "abc") ; => t
```
