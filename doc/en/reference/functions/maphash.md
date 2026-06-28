# maphash

`(maphash function table)`

Calls `function` once for each entry in `table`, passing the key and value as its two arguments, for side effects only; the results are discarded and `maphash` returns nil. Iteration order is unspecified and may differ across backends, so portable code should not rely on it.

```lisp
(let ((h (make-hash-table)))
  (setf (gethash 'a h) 1)
  (maphash (lambda (k v) (print (list k v))) h))
```

```
(a 1)
```
