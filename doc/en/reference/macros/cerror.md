# cerror

`(cerror continue-format-control datum arg...)`

Signals an error like [`error`](error.md) with the same condition-designator surface: `datum` is a format control string (with `arg...` as format arguments) or a condition class name (with `arg...` as initargs). In Common Lisp `cerror` establishes a `continue` restart described by `continue-format-control`; rontolisp has no restart machinery, so the error is **not continuable** and the continue format control is accepted and dropped — `(cerror "Ignore it." "boom ~a" 1)` behaves exactly like `(error "boom ~a" 1)`.

Because an uncaught `cerror` aborts execution it is shown here statically rather than as a runnable example:

```console
(cerror "Ignore the error." "bad value: ~a" 42)
(cerror "Skip this character." 'bad-input :position 7)
```
