# with-standard-io-syntax

`(with-standard-io-syntax form...)`

Evaluates the body as a `progn`. In Common Lisp this macro dynamically rebinds the whole reader/printer control set to standard values so that a body reads and prints independently of the caller's settings; in rontolisp there is nothing for it to rebind. `*package*` is resolved before the program runs and is not a run-time cell, `*read-default-float-format*` is informational (every float shares the one double representation), `*print-circle*` and `*readtable*` exist so library code that reads them loads, and `*print-escape*`/`*print-readably*` exist and hold their standard values (`t`/`nil`) — `*print-escape*` is the one printer variable rontolisp really binds, around a [`print-object`](../functions/print-object.md) method call, so the method can tell [`prin1`](../functions/prin1.md) from [`princ`](../functions/princ.md). The remaining standard variables — `*print-base*`, `*read-base*` and friends — do not exist at all, which amounts to being permanently standard.

The macro is therefore an identity wrapper, provided so that portable Common Lisp code using it loads and runs unchanged.

```lisp
(with-standard-io-syntax
  (prin1-to-string (list 1 2 3))) ; => "(1 2 3)"
```
