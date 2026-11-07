# equalp

`(equalp x y)`

Like [`equal`](equal.md), but strings and characters are compared case-insensitively and numbers by numeric value (`=`). Cons cells are compared recursively. Arrays are compared element by element with `equalp` when their dimensions match. Structures are compared slot by slot with `equalp`, and so are CLOS instances (Common Lisp compares those by identity). Lite: hash tables fall back to `eql`.

```lisp
(equalp #(1 "A") #(1 "a")) ; => T
```
