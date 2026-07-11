# usocket:socket-stream usocket:socket-close

`(usocket:socket-stream socket)` -- `(usocket:socket-close socket)`

`socket-stream` returns the stream associated with a socket; `socket-close`
flushes and closes it. In this shim a socket IS its stream handle (the
`rontolisp:tcp-*` handles share the file-stream handle space), so
`socket-stream` is the identity function -- it exists so portable usocket code
like `(read-line (usocket:socket-stream sock))` runs unchanged -- and
`socket-close` is `close`.

```lisp
(usocket:socket-stream 42) ; => 42
```

```console
(let ((stream (usocket:socket-stream sock)))
  (write-line "ping" stream)
  (print (read-line stream))
  (usocket:socket-close sock))
```

## Backend support

- **Interpreter**, **JVM** and **WASM component**: wherever the socket itself
  works (`socket-stream` is pure and works everywhere).
- **Browser playground**: `socket-stream` works; sockets do not.
