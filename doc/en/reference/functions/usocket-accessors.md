# usocket:get-local-port usocket:get-local-address usocket:get-local-name usocket:get-peer-address usocket:get-peer-port usocket:get-peer-name

`(usocket:get-local-port socket)` -- `(usocket:get-local-address socket)` -- `(usocket:get-local-name socket)` -- `(usocket:get-peer-address socket)` -- `(usocket:get-peer-port socket)` -- `(usocket:get-peer-name socket)`

The usocket address accessors, over the
[`rontolisp:tcp-local-port`](rontolisp-tcp-local-port.md) /
[`tcp-local-address` / `tcp-peer-address` / `tcp-peer-port`](rontolisp-tcp-addresses.md)
built-ins. The `get-local-*` accessors work on listeners and connected sockets
(reading an ephemeral port back after listening on `usocket:*auto-port*` is
the main use); the `get-peer-*` accessors work on connected sockets only.

```lisp
(let* ((listener (usocket:socket-listen "127.0.0.1" usocket:*auto-port*))
       (port (usocket:get-local-port listener)))
  (usocket:socket-close listener)
  (> port 0)) ; => t
```

`get-local-name` / `get-peer-name` return `(values address port)`: a
`multiple-value-bind` receives both parts, and an ordinary single-value
context receives the address.

```lisp
(let ((listener (usocket:socket-listen "127.0.0.1" usocket:*auto-port*)))
  (multiple-value-bind (address port) (usocket:get-local-name listener)
    (usocket:socket-close listener)
    (list address (> port 0)))) ; => ("127.0.0.1" t)
```

## Backend support

- **Interpreter** and **JVM**: full support.
- **WASM**: component mode only; all six accessors return real addresses and
  ports, like the interpreter/JVM (a failure returns `nil` instead of
  signaling). Preview 1 is a compile error.
- **Browser playground**: not supported.
