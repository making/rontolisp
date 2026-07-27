# Hostname resolution for the WASM tcp built-ins

`rontolisp:tcp-connect` / `tcp-listen` accept hostnames on the interpreter/JVM
(`java.net.Socket` / `InetAddress` resolve natively) but only IPv4 literals on the
WASM component backend: `rontolisp::%sock-parse-ipv4`
(`src/main/resources/am/ik/rontolisp/eval/sockets.lisp`, line 71) walks the dotted
quad itself and returns nil for anything else, whereupon `%sock-addr` (line 95)
signals `'rontolisp:wit-error :payload :invalid-argument` with "tcp: not an IPv4
literal: ". (`localhost` is special-cased to `127.0.0.1` before the parse.)

To lift that, wire `wasi:sockets/ip-name-lookup@0.3.0` -- vendored at
`src/wasm-component/deps/sockets/ip-name-lookup.wit` but imported nowhere:

1. append the `ip-name-lookup` interface to
   `src/main/resources/am/ik/rontolisp/eval/sockets.wit`,
2. bind `resolve-addresses` in `sockets.lisp` beside the existing `%sock:` members,
3. call it from `%sock-addr` when `%sock-parse-ipv4` returns nil, taking the first
   address, and keep the `wit-error` for a lookup that yields none.

The one compiler-side cost: `resolve-addresses` returns a `stream<ip-address>` --
a non-u8, non-handle element type -- so `validateAsyncAlias` / `emitStreamRead`
(`codegen/wasm/WasmComponentImportCompiler`) need extending past the accept
stream's kind-2 handle lift, which is currently the ONE non-u8 stream lift
(`.kb/tcp-sockets.md`). wasmtime hosts the interface behind
`-S allow-ip-name-lookup=y`.

## How it fails today, measured 2026-07-27

Through the BUILT-IN the failure is graceful: `(rontolisp:tcp-connect
"a-host-name" 5432)` on a component prints `NIL` (the `wit-error` is caught and
surfaces as nil, like every other permission/socket failure). Through a LIBRARY
it is not: the same hostname handed to cl-postgres (usocket shim -> `%sock-addr`)
dies as `wasm trap: cast failure` with only a numeric backtrace -- the nil socket
flows on into the driver, which casts it. So a user who hands a compiled
component a hostname gets an unreadable trap, not "not an IPv4 literal", and that
error-quality gap is the second reason to wire the lookup (the first is the
feature itself). Encountered while writing `ClPostgresE2eTest`, whose component
leg therefore connects to the container's IP address rather than its network
alias.

## Status

Still open, but SMALLER than originally written. The old plan (import into
`uni-sockets.wit`, read the first address in `adapter-sockets.wat`'s
`$tcp_connect`, regenerate the blobs, re-derive the
`WasmComponentBuilder.buildSock` constants) is dead: commit c84708c deleted the
hand-written adapter and the whole sockets blob variant. A tcp program is now the
base variant plus one appended user WIT import, so this is Lisp + WIT work plus
the one stream-lift extension -- no blob regeneration.
