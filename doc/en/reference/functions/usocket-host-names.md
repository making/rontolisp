# usocket:host-to-hostname usocket:get-host-by-name

`(usocket:host-to-hostname host)` -- `(usocket:get-host-by-name name)`

The usocket host-designator pair. `host-to-hostname` renders any designator
upstream accepts as a hostname/dotted-quad string: `nil` is the wildcard host
`"0.0.0.0"`, a string passes through, a vector quad (or list of four octets)
and a host-byte-order 32-bit integer become the dotted quad.

```lisp
(list (usocket:host-to-hostname nil)
      (usocket:host-to-hostname "example.com")
      (usocket:host-to-hostname #(192 168 0 1))
      (usocket:host-to-hostname 2130706433)) ; => ("0.0.0.0" "example.com" "192.168.0.1" "127.0.0.1")
```

`get-host-by-name` is **lite**: rontolisp has no name-resolution primitive on
any backend, so it renders its argument through `host-to-hostname` instead of
resolving it to upstream's vector quad. That keeps the normalize-then-hand-on
chain libraries use -- `(usocket:host-to-hostname (usocket:get-host-by-name
address))` -- an identity on the address it is given, and the
[`usocket:socket-connect`](usocket-socket-connect.md) /
[`usocket:socket-listen`](usocket-socket-listen.md) call that address reaches
still resolves it for real (natively on the interpreter and the JVM; IPv4
literals only on WASM).

```lisp
(usocket:host-to-hostname (usocket:get-host-by-name "127.0.0.1")) ; => "127.0.0.1"
```

## Backend support

Works on all four backends, and answers identically on each: both are pure Lisp
in the shim and neither opens a socket, so unlike the rest of the usocket API
they are available on WASM Preview 1 too.
