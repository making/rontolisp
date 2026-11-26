# rontolisp:finally

`(rontolisp:finally future thunk)`

Returns a fresh future carrying the input's original settlement (either
value or condition) and runs the zero-argument `thunk` exactly once on
whichever outcome the future produces. The thunk's return value is
discarded; a condition raised inside the thunk **replaces** the pending
outcome (matches `unwind-protect`).

```lisp
(defvar *cleanup-log* nil)
(rontolisp:async-defun produce () 5)
(let ((v (rontolisp:await
           (rontolisp:finally (produce)
                              (lambda () (push :done *cleanup-log*))))))
  (list v (reverse *cleanup-log*)))   ; => (5 (:DONE))
```

Use it to run a cleanup step (release a resource, log a metric, decrement
a counter) that must fire on both the success and the error channels of a
future you receive from a callee.

A non-future first argument is a `type-error`.

## Backend support

Same as [`rontolisp:then`](rontolisp-then.md): interpreter, JVM, WASM
`--component`. Preview 1 WASM supports the success shape (the error arm
would need the futured error-at-await contract that the component backend
provides). `--no-gc` rejects at compile time.
