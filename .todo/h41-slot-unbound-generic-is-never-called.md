# h41. `slot-unbound` (and `slot-missing`) methods are never called

Difficulty: Medium

Found 2026-10-30 probing `zpng`: it fills `data-array` lazily from
`(defmethod slot-unbound (class (png png) (slot (eql 'data-array))) ...)`. Here the reader
signals `The slot DATA-ARRAY is unbound` without calling the method.

```lisp
(defclass png () ((data :reader data :writer (setf %data))))
(defmethod slot-unbound (class (p png) (slot (eql 'data))) (setf (%data p) (list :lazy)))
(data (make-instance 'png))   ; SBCL: (:LAZY)
```

Both are also undefined as functions (h39). Make an unbound read (reader, `slot-value`,
`with-slots`) call the `slot-unbound` generic, and an access to an unknown slot call
`slot-missing`. Each falls back to the current signal, on all four backends (`.kb/clos.md`).
Once this works, re-run zpng: its `write-png` path also needs `(unsigned-byte 8)` file
output (untested).
