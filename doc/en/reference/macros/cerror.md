# cerror

`(cerror continue-format-control datum arg...)`

Signals a **continuable** error like [`error`](error.md), with the same condition-designator surface: `datum` is a format control string (with `arg...` as format arguments) or a condition class name (with `arg...` as initargs). A `continue` restart described by `continue-format-control` is established around the signal, so a [`handler-bind`](handler-bind.md) handler — or anything else running at the signal point — can call [`continue`](../functions/continue.md) (or `invoke-restart` the `continue` restart) to make the `cerror` return `nil` and execution resume past it. When nothing invokes the restart, `cerror` behaves exactly like `error`: an uncaught one aborts, an enclosing [`handler-case`](handler-case.md) catches it.

```lisp
(handler-bind ((error (lambda (c) (continue))))
  (list :after (cerror "Ignore the error." "bad value: ~a" 42))) ; => (:AFTER NIL)
```

Uncaught, it aborts like `error` (shown statically):

```console
(cerror "Ignore the error." "bad value: ~a" 42)
(cerror "Skip this character." 'bad-input :position 7)
```
