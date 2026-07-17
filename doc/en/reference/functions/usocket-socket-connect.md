# usocket:socket-connect

`(usocket:socket-connect host port &key protocol element-type timeout deadline nodelay local-host local-port)`

Opens a blocking TCP connection to `host` and `port` and returns a socket --
the [usocket](https://github.com/usocket/usocket)-compatible entry point over
[`rontolisp:tcp-connect`](rontolisp-tcp-connect.md). In this shim a socket IS
its stream handle, so `usocket:socket-stream` is the identity function and the
stream built-ins (`read-line`, `write-line`, `write-string`, `read-byte`,
`write-byte`, `close`) work on it directly.

Only TCP is supported: `:protocol :datagram` (UDP) signals an error. The other
keyword arguments are accepted for compatibility and ignored --
`:element-type` in particular, because a rontolisp socket handle is always
bidirectional and supports both the line and the byte built-ins (there is no
separate binary/character stream construction to select).

The cl-postgres (Postmodern) connection shape works verbatim:

```console
(usocket:socket-stream
 (usocket:socket-connect "localhost" 5432
                         :element-type '(unsigned-byte 8)))
```

A loopback echo round trip (single-threaded choreography: connect before
accept, write before the peer reads):

```lisp
(let* ((listener (usocket:socket-listen "127.0.0.1" usocket:*auto-port*))
       (port (usocket:get-local-port listener))
       (client (usocket:socket-connect "127.0.0.1" port :element-type '(unsigned-byte 8))))
  (write-line "hello" (usocket:socket-stream client))
  (let* ((server (usocket:socket-accept listener))
         (line (read-line (usocket:socket-stream server))))
    (usocket:socket-close server)
    (usocket:socket-close client)
    (usocket:socket-close listener)
    line)) ; => "hello"
```

The `usocket` package is loaded automatically on first use (interpreter) or
spliced into the compiled program (JVM / WASM component), and is also
registered as the built-in ASDF system `"usocket"`, so
`(asdf:load-system "usocket")`, `(ql:quickload :usocket)` and a third-party
`.asd`'s `:depends-on ("usocket")` all resolve to it without touching the
network. rontolisp has no condition system, so `usocket:socket-error` and the
other condition types exist as data symbols only -- a connection failure
signals an error (interpreter / JVM) or returns `nil` (WASM component), and
`handler-case` over usocket conditions is not supported.

## Backend support

- **Interpreter** and **JVM**: full support (delegates to
  `rontolisp:tcp-connect`).
- **WASM**: component mode only (`--component`), IPv4 literals only; a failed
  connection returns `nil`. Preview 1 is a compile error.
- **Browser playground**: not supported.
