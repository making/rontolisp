# assert

`(assert test-form [(place...) [datum args...]])`

Evaluates `test-form` and signals an error when it is false; returns nil when it is true. The optional datum and arguments work like `error`'s control string and arguments and replace the default "The assertion ... failed." message. This is the lite version of Common Lisp's `assert`: it establishes no `continue` restart, so the places list is accepted but ignored (no interactive re-store loop).

```lisp
(let ((x 1)) (assert (> x 0)) x) ; => 1
```

A failing assertion aborts execution, so it is shown statically:

```console
(let ((x 0)) (assert (> x 0) (x) "x must be positive, got ~a" x))
; error: x must be positive, got 0
```
