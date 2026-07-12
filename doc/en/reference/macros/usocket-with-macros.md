# usocket:with-client-socket usocket:with-connected-socket usocket:with-server-socket usocket:with-socket-listener

`(usocket:with-client-socket (socket-var stream-var host port &rest connect-args) body...)` --
`(usocket:with-connected-socket (var socket-form) body...)` --
`(usocket:with-server-socket (var socket-form) body...)` --
`(usocket:with-socket-listener (socket-var host port &rest listen-args) body...)`

The usocket convenience macros: each binds a socket for the extent of the
body and closes it afterwards. `with-client-socket` connects (passing
`connect-args` through to `usocket:socket-connect`) and additionally binds
`stream-var` to the socket's stream (pass `nil` to skip that binding);
`with-socket-listener` listens (passing `listen-args` through to
`usocket:socket-listen`); `with-connected-socket` and `with-server-socket`
(aliases in this shim) wrap an existing socket form such as a
`usocket:socket-accept` call.

```lisp
(usocket:with-socket-listener (listener "127.0.0.1" 0)
  (usocket:with-client-socket (client stream "127.0.0.1" (usocket:get-local-port listener))
    (write-line "ping" stream)
    (usocket:with-connected-socket (server (usocket:socket-accept listener))
      (read-line server)))) ; => "ping"
```

On the interpreter and the JVM the expansion wraps the body in
[`unwind-protect`](../special-forms/unwind-protect.md), so the socket is
closed on **every** exit -- normal return, an error signaled inside the body,
or a `return`/`return-from` (usocket proper's semantics). On the WASM
component backend, where `unwind-protect` does not compile, the socket is
closed on normal exit only. Like `rontolisp:with-arena`, these are built-in
macro expansions, so they cannot be passed to `funcall`/`apply`.

## Backend support

- **Interpreter**, **JVM** and **WASM component**: wherever the underlying
  socket functions work (the expansion is shared by all backends; the WASM
  variant closes on normal exit only, see above).
- **Browser playground**: not supported.
