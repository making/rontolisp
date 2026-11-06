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

- **Interpreter** and **JVM**: real, via `Socket.setSoTimeout`; a count past
  2147483647 waits 2147483647 milliseconds. `milliseconds` outside
  `(OR NULL (INTEGER 0))` signals the operator's `type-error`, and a value that
  is not an open connected socket handle a `simple-error`.
- **WASM component**: a real read deadline too. `wasi:sockets@0.3.0` has no
  receive timeout of its own, so a read that has to wait for its data races a
  timer; a read past the deadline signals `read-line: Read timed out` (the
  interpreter's text), and the socket can be read again afterwards, as on the
  interpreter and the JVM. `0` clears the deadline like `nil`. A value that is
  not an open connected socket answers `nil` there (the component's error
  convention), and `milliseconds` that is not `nil` or a non-negative integer
  signals a `simple-error`. Call-time error in Preview 1 (core-module) mode,
  like every tcp built-in.
- **Native executable** (`--native`): a real read deadline, as on the
  interpreter and the JVM; a read past it signals `read-line: Read timed out`
  ([TCP sockets in a native executable](../../compiling/native.md#tcp-sockets)).
- **Browser playground**: not supported.
