# d92. #'name of 29 standard functions does not compile

Difficulty: Medium

```lisp
(print (funcall #'arrayp 1))       ; interpreter and SBCL: NIL
(print (funcall 'close (make-string-output-stream)))
```

The JVM, P1 and the component refuse both with `Cannot compile: ARRAYP as a function value
(this backend has none for the built-in)`: these are standard functions the backends compile
in head position only. Measured over every `cl` function the interpreter defines
(`(function NAME)` in a defun each, JVM and P1 identical): `array-dimensions arrayp close eval
export fmakunbound get-internal-real-time get-internal-run-time get-output-stream-string
get-universal-time hash-table-rehash-size hash-table-rehash-threshold hash-table-size
hash-table-test import load make-random-state make-string-input-stream
make-string-output-stream make-synonym-stream open-stream-p provide rationalp require
row-major-aref unexport unuse-package use-package while`. `symbol-function` and `fdefinition`
are the same gap, filed as `.todo/d81`.

## Plan

- Four-backend fixture first: each name as `#'name` and as a quoted designator.
- A `BuiltinFunctionWrappers` entry each, reference-gated where the body pulls in a runtime
  (`.kb/adding-primitives.md`); `#'write-char` / `#'write-byte` are the precedent.
