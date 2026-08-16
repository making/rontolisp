# rontolisp:tcp-set-timeout

`(rontolisp:tcp-set-timeout handle milliseconds)`

Sets the read deadline of a connected socket handle: every subsequent blocking
read on the handle (`read-line`, `read-char`, `read-byte`, ...) signals an
error after `milliseconds` without data instead of waiting forever.
`milliseconds` is a non-negative integer (the [`rontolisp:wait-for`](rontolisp-wait-for.md)
convention), and `nil` clears the deadline. Returns the `milliseconds`
argument. Listener handles are not accepted (the deadline is a read deadline).

```lisp
(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
       (port (rontolisp:tcp-local-port listener))
       (sock (rontolisp:tcp-connect "127.0.0.1" port)))
  (rontolisp:tcp-set-timeout sock 200)
  (prog1 (handler-case (progn (read-line sock) :read)
           (error (e) :timed-out))   ; nothing is ever written -> the deadline fires
    (close sock)
    (close listener)))   ; => :TIMED-OUT
```

The timeout error is a plain catchable `error` whose message names the read
that timed out; it is not a distinct condition class. The deadline lives on
the raw socket, so it keeps governing a connection later upgraded with
[`rontolisp:tls-upgrade`](rontolisp-tls-upgrade.md). This is the primitive
behind the usocket shim's
`(setf (usocket:socket-option sock :receive-timeout) seconds)` (see the
[TCP Sockets guide](../../guides/tcp-sockets.md#the-usocket-compatible-shim)).

## Backend support

- **Interpreter** and **JVM**: real, via `Socket.setSoTimeout`.
- **WASM component**: SIGNALS at call time — `wasi:sockets@0.3.0` exposes no
  receive-timeout knob, and a timeout that silently never fires is the failure
  mode a client sets it to avoid. Catch it (or do not set a read timeout) on
  this backend. Call-time error in Preview 1 (core-module) mode, like every
  tcp built-in.
- **Browser playground**: not supported.
