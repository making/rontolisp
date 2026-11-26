# copy-readtable

`(copy-readtable &optional (from-readtable *readtable*) to-readtable)`

Returns a copy of a readtable: its case and every macro character, dispatch table and syntax it gives a character. `nil` as `from-readtable` designates the standard readtable, so `(copy-readtable nil)` is a fresh standard one. Given `to-readtable`, that readtable is overwritten and returned. Works on all four backends.

```lisp
(readtablep (copy-readtable nil)) ; => T
```
