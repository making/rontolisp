# rontolisp:tcp-local-address rontolisp:tcp-peer-address rontolisp:tcp-peer-port

`(rontolisp:tcp-local-address handle)` -- `(rontolisp:tcp-peer-address handle)` -- `(rontolisp:tcp-peer-port handle)`

Address introspection for TCP handles. `tcp-local-address` returns the local
(bound) IP address of a listener or socket handle as a string;
`tcp-peer-address` and `tcp-peer-port` return the remote IP address (a string)
and remote port (an integer) of a connected socket handle. Together with
[`rontolisp:tcp-local-port`](rontolisp-tcp-local-port.md) they back the
`usocket:get-local-*` / `usocket:get-peer-*` accessors of the
[usocket shim](usocket-accessors.md).

```lisp
(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
       (port (rontolisp:tcp-local-port listener))
       (client (rontolisp:tcp-connect "127.0.0.1" port))
       (server (rontolisp:tcp-accept listener))
       (peer (rontolisp:tcp-peer-address client)))
  (close server)
  (close client)
  (close listener)
  peer) ; => "127.0.0.1"
```

The peer accessors reject a listener handle (a listener has no peer):

```console
$ rontolisp
CL-USER> (setq l (rontolisp:tcp-listen 0 "127.0.0.1"))
CL-USER> (rontolisp:tcp-peer-address l)
Error: tcp-peer-address expects a connected socket handle
```

## Backend support

- **Interpreter** and **JVM**: `getLocalAddress()` / `getInetAddress()` /
  `getPort()` on the underlying `java.net.Socket` / `ServerSocket`. A handle
  that is not the right kind of socket signals an error (interpreter) or fails
  with a cast error (JVM).
- **WASM**: component mode only -- all three return real addresses and ports,
  exactly like the interpreter/JVM. On failure or a wrong kind of handle they
  return `nil` instead of signaling (so a spliced usocket program still runs
  there). Call-time error in Preview 1 (core-module) mode, like every tcp
  built-in.
- **Browser playground**: not supported.
