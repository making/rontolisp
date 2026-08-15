# with-standard-io-syntax

`(with-standard-io-syntax form...)`

Binds `*package*` to `cl-user` and evaluates the body as a `progn`. In Common Lisp this macro dynamically rebinds the whole reader/printer control set to standard values so that a body reads and prints independently of the caller's settings; in rontolisp `*package*` is the one variable of that set with a run-time value to rebind (a body's `intern`/`read` homes in `cl-user`, as in Common Lisp). `*read-default-float-format*` is informational (every float shares the one double representation), `*print-circle*` and `*readtable*` exist so library code that reads them loads, and `*print-escape*`/`*print-readably*` exist and hold their standard values (`t`/`nil`) — `*print-escape*` is the one printer variable rontolisp really binds, around a [`print-object`](../functions/print-object.md) method call, so the method can tell [`prin1`](../functions/prin1.md) from [`princ`](../functions/princ.md). The remaining printer-mode variables (`*print-base*`, ...) hold their standard values, and the reader variables (`*read-base*` and friends) do not exist at all, which amounts to being permanently standard.

Deviation: `*print-escape*`/`*print-readably*`/`*print-pretty*`, which the printer does honor, are not rebound here, so a non-standard binding of one of them around this form leaks into the body.

```lisp
(with-standard-io-syntax
  (prin1-to-string (list 1 2 3))) ; => "(1 2 3)"
(with-standard-io-syntax *package*) ; => :CL-USER
```
