# copy-readtable

`(copy-readtable &optional from to)`

Lite stub: a no-op returning `nil` — the reader is not readtable-driven, so there is no readtable object to copy (the `*readtable*` variable exists but is seeded to `nil`). The arguments are still evaluated. Exists so the common library header idiom `(defparameter *my-readtable* (copy-readtable nil))` loads.

```lisp
(copy-readtable nil) ; => NIL
```
