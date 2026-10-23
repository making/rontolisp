# rontolisp:tcp-local-port

`(rontolisp:tcp-local-port handle)`

Returns the local TCP port bound to a listener or socket handle as an
integer. Its main use is reading the actual port back after listening on port
`0` (an ephemeral port picked by the operating system), which is how a test or
a self-contained example avoids hard-coding a port:

```lisp
(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
       (port (rontolisp:tcp-local-port listener)))
  (close listener)
  (> port 0))   ; => T
```

It also works on a connected socket handle, where it reports the local
(client-side) port of the connection:

```console
(let ((sock (rontolisp:tcp-connect "127.0.0.1" 7777)))
  (print (rontolisp:tcp-local-port sock))   ; the ephemeral client port
  (close sock))
```

## Backend support

- **Interpreter** and **JVM**: `getLocalPort()` on the underlying
  `java.net.ServerSocket` / `Socket`. A value that is not an open socket or
  listener handle signals the `simple-error`
  `TCP-LOCAL-PORT expects a socket or listener handle` on both.
- **WASM**: component-only, via `wasi:sockets`' `get-local-address`; returns
  `nil` for a handle that is not a socket or listener. Call-time error in
  Preview 1 (core-module) mode, like every tcp built-in.
- **Native executable** (`--native`): as on the interpreter and the JVM, the
  `simple-error` for a wrong kind of handle included
  ([TCP sockets in a native executable](../../compiling/native.md#tcp-sockets)).
- **Browser playground**: not supported.
