# usocket:socket-accept

`(usocket:socket-accept socket &key element-type)`

Blocks until a client connects to the given listener and returns the accepted
connection socket -- the usocket-compatible wrapper over
[`rontolisp:tcp-accept`](rontolisp-tcp-accept.md). `:element-type` is accepted
for compatibility and ignored (a rontolisp socket handle is always
bidirectional).

```lisp
(let* ((listener (usocket:socket-listen "127.0.0.1" usocket:*auto-port*))
       (port (usocket:get-local-port listener))
       (client (usocket:socket-connect "127.0.0.1" port))
       (server (usocket:socket-accept listener))
       (peer (usocket:get-peer-address server)))
  (usocket:socket-close server)
  (usocket:socket-close client)
  (usocket:socket-close listener)
  peer) ; => "127.0.0.1"
```

## Backend support

- **Interpreter** and **JVM**: full support.
- **WASM**: component mode only; Preview 1 is a compile error.
- **Browser playground**: not supported.
