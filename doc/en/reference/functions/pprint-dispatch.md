# copy-pprint-dispatch set-pprint-dispatch pprint-dispatch

`(copy-pprint-dispatch &optional table)` -- `(set-pprint-dispatch type-specifier function &optional priority table)` -- `(pprint-dispatch object &optional table)`

The pretty-print dispatch table: a set of `(type-specifier function priority)` entries saying how to print a value of a given type. `copy-pprint-dispatch` answers a fresh copy of `*print-pprint-dispatch*` (or of the empty initial table when given `nil`), `set-pprint-dispatch` adds an entry -- or removes one when `function` is `nil` -- and `pprint-dispatch` answers the highest-priority entry function matching an object, plus a second value saying whether one was found. An entry function is called as `(funcall function stream object)`.

The ordinary printing operators (`print`, `princ`, `prin1`, `~A`, `~S`) do **not** consult the table: an entry takes effect where the program calls the entry function itself.

```lisp
(let ((table (copy-pprint-dispatch)))
  (set-pprint-dispatch 'integer (lambda (stream x) (princ (* 2 x) stream)) 0 table)
  (with-output-to-string (out) (funcall (pprint-dispatch 21 table) out 21))) ; => "42"
```
