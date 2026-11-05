# h84. `psetf` of a `with-slots` place calls an undefined `%slot-value-set-runtime` when compiled

Difficulty: Medium

```lisp
(defclass n () ((a :initarg :a) (b :initarg :b)))
(defgeneric sw (x) (:method ((x n)) (with-slots (a b) x (psetf a b b a) (list a b))))
(print (sw (make-instance 'n :a 1 :b 2)))
```

Measured again 2026-11-05, unchanged. Interpreter `(2 1)`. JVM: `warning: the function %SLOT-VALUE-SET-RUNTIME is undefined` at
compile time, the same error at run time; WASM traps the same way. The shared
`%slot-value-set-runtime` defun is built only for the shapes `LispMacroExpander` recognizes (a
direct single-pair `setf`); the `psetf` expansion reaches it without triggering its generation.

Blocks cl-heap's `fibonacci-heap` on JVM/WASM (`fibonacci-heap.lisp:99`, `delete-node`). Fix the
trigger for every expansion that can produce the call (`psetf`, `rotatef`, `shiftf`,
`multiple-value-setq` over slots), then re-run cl-heap's priority-queue + fibonacci-heap probe on
all four.
