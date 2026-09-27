# wasm `open-stream-p` answers T after `close`

Difficulty: Medium

```lisp
(let ((s (make-string-output-stream))) (close s) (print (open-stream-p s)))
(let ((s (make-string-input-stream "abc"))) (close s) (print (open-stream-p s)))
(let ((s (open "f.txt"))) (close s) (print (open-stream-p s)))
```

| interpreter / JVM | wasm / component (2026-09-27) |
|---|---|
| `NIL` x3 | `T` x3 |

`WasmExprCompiler`'s `OPEN_STREAM_P` arm compiles `LispMacroExpander.expandOpenStreamPLite`: with no
per-handle open/closed record, any non-nil designator answers T. Only the sockets splice
(`WasmSocketsRewrite` -> `%io-open-stream-p`) keeps a table. `.kb/scheme-frontend.md` ("Ports")
records the gap as a reason Scheme ports carry their own `open` slot.

Goal: `close` marks the handle closed on wasm (string streams and fd streams alike) and
`open-stream-p` reads the mark, pinned on all four backends.
