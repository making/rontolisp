# with-hash-table-iterator

`(with-hash-table-iterator (name hash-table) body...)`

Binds `name` to a LOCAL FUNCTION (an `flet`, not CL's `macrolet`) that answers one entry per call as `(values t key value)`, and `(values nil nil nil)` once the table is exhausted. Because it is an ordinary local function it can also be passed as a value, which CL's `macrolet` binding cannot.

The table is SNAPSHOT on entry, so an entry added or removed during the walk is not seen -- CLHS leaves that case undefined. The order is unspecified.

```lisp
(let ((h (make-hash-table)))
  (setf (gethash 'a h) 1)
  (with-hash-table-iterator (next h)
    (multiple-value-bind (morep k v) (next)
      (list morep k v)))) ; => (T A 1)
```
