# readtablep

`(readtablep object)`

True when `object` is a readtable. Works on all four backends.

```lisp
(list (readtablep *readtable*) (readtablep nil)) ; => (T NIL)
```
