# usocket:socket-listen

`(usocket:socket-listen host port &key reuse-address backlog element-type)`

Binds a listening TCP socket on `host` and `port` and returns a listener --
the usocket-compatible wrapper over
[`rontolisp:tcp-listen`](rontolisp-tcp-listen.md) (note the flipped argument
order: usocket passes the host first). A `host` of `usocket:*wildcard-host*`
(`"0.0.0.0"`) or `nil` listens on all interfaces; a `port` of
`usocket:*auto-port*` (`0`) picks a free ephemeral port, which
`usocket:get-local-port` reads back. The keyword arguments are accepted for
compatibility and ignored (the backlog is the runtime default).

```lisp
(let* ((listener (usocket:socket-listen usocket:*wildcard-host* usocket:*auto-port*))
       (port (usocket:get-local-port listener)))
  (usocket:socket-close listener)
  (> port 0)) ; => T
```

Accept connections with [`usocket:socket-accept`](usocket-socket-accept.md).

## Backend support

- **Interpreter** and **JVM**: full support.
- **WASM**: component mode only; a failed bind returns `nil`. Preview 1 is a
  compile error.
- **Browser playground**: not supported.
