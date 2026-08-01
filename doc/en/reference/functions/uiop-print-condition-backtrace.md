# uiop/image:print-condition-backtrace

`(uiop/image:print-condition-backtrace condition &key stream count)`

Prints a report for `condition` to `stream` (default `*error-output*`).
**Lite**: no backend carries a Lisp-level
call stack, so there is no backtrace to print and the report is the condition
alone; `count` is accepted and ignored. Real UIOP falls back to the same shape
on an implementation with no backtrace API.

```lisp
(handler-case (error "boom")
  (error (c) (uiop/image:print-condition-backtrace c :stream *standard-output*)))
```

```
boom
```

The name lives in the `uiop/image` package, as upstream, and the `uiop` package
re-exports it -- `uiop:print-condition-backtrace` names the same function.
`lack-middleware-backtrace` is what asks for it.

## Backend support

Works on all four backends: it is a prelude definition written in rontolisp
itself and compiled into the program when used.
