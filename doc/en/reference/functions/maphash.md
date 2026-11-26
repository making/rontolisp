# maphash

`(maphash function table)`

Calls `function` once for each entry in `table`, passing the key and value as its two arguments, for side effects only; the results are discarded and `maphash` returns nil. Every backend walks the entries in the order their keys were first stored: storing under a key the table already has keeps its place, and a key removed and stored again comes last. ANSI leaves the order unspecified (SBCL, for one, reuses a removed entry's slot), so portable code should not rely on it.

```lisp
(let ((h (make-hash-table)))
  (setf (gethash 'a h) 1)
  (maphash (lambda (k v) (print (list k v))) h))
```

```
(A 1)
```
