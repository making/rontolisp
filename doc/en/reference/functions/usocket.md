# usocket Package Functions

The `usocket` package is a compatibility shim over the `rontolisp:tcp-*`
built-ins reproducing the [usocket](https://github.com/usocket/usocket) API,
so existing Common Lisp networking code (such as Postmodern's cl-postgres
socket layer) runs with fewer changes. It is **not part of Common Lisp**;
reference its symbols with the `usocket:` qualifier. A socket IS its stream
handle here, so `socket-stream` is the identity function and the standard
stream functions work on sockets directly. The package is loaded on first use
and is also the built-in ASDF system `"usocket"` (satisfying
`asdf:load-system`, `ql:quickload` and `:depends-on ("usocket")` without a
download). TCP only -- UDP (`socket-send` / `socket-receive`),
`wait-for-input`, `socket-server` and the condition hierarchy
(`usocket:socket-error` under `handler-case`) are not supported. The variables
`usocket:*wildcard-host*` (`"0.0.0.0"`) and `usocket:*auto-port*` (`0`) are
provided. See the
[TCP Sockets guide](../../guides/tcp-sockets.md#the-usocket-compatible-shim) for
a worked overview and the full limitation list.

| Function | Example | Result |
|----------|---------|--------|
| `usocket:socket-connect` | `(usocket:socket-connect "localhost" 5432 :element-type '(unsigned-byte 8))` | open a blocking TCP connection; `:protocol :datagram` signals, the other options are accepted and ignored |
| `usocket:socket-listen` | `(usocket:socket-listen usocket:*wildcard-host* usocket:*auto-port*)` | bind a listening TCP socket (host first, usocket-style) |
| `usocket:socket-accept` | `(usocket:socket-accept listener)` | wait for a client connection (blocking) |
| `usocket:socket-stream` | `(read-line (usocket:socket-stream sock))` | the stream of a socket (the identity function in this shim) |
| `usocket:socket-close` | `(usocket:socket-close sock)` | close a socket or listener |
| `usocket:get-local-port` | `(usocket:get-local-port listener)` | the locally bound port (read an ephemeral port back) |
| `usocket:get-local-address` | `(usocket:get-local-address listener)` | the locally bound IP address, as a string |
| `usocket:get-peer-address` | `(usocket:get-peer-address sock)` | the remote IP address of a connected socket |
| `usocket:get-peer-port` | `(usocket:get-peer-port sock)` | the remote port of a connected socket |
| `usocket:get-local-name` | `(usocket:get-local-name sock)` | local address and port as `(values address port)` |
| `usocket:get-peer-name` | `(usocket:get-peer-name sock)` | remote address and port as `(values address port)` |
| `usocket:host-to-hostname` | `(usocket:host-to-hostname #(192 168 0 1))` | a host designator (string, vector quad, host-byte-order integer or `nil`) as a hostname/dotted-quad string |
| `usocket:get-host-by-name` | `(usocket:get-host-by-name "example.com")` | lite: renders the designator through `host-to-hostname` instead of resolving it — no backend has a name-resolution primitive, and the socket call the address reaches resolves it for real |

The `with-*` convenience macros (`usocket:with-client-socket` /
`with-connected-socket` / `with-server-socket` / `with-socket-listener`) are
listed on the [macros page](../macros.md) and described on their
[reference page](../macros/usocket-with-macros.md); on the interpreter and the
JVM they close the socket on every exit (they expand over
[`unwind-protect`](../special-forms/unwind-protect.md)), on the WASM component
backend on normal exit only.

