# get-dispatch-macro-character

`(get-dispatch-macro-character disp-char sub-char &optional (readtable *readtable*))`

The reader function of `sub-char` under the dispatch macro character `disp-char`, or `nil` when it has none (a decimal digit always has none). A `disp-char` that is not a dispatch macro character signals. A standard sub-character answers its standard function, the same object in every readtable. Works on all four backends.

```lisp
(list (functionp (get-dispatch-macro-character #\# #\')) (get-dispatch-macro-character #\# #\0))
; => (T NIL)
```
