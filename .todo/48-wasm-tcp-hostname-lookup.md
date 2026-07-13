# Hostname resolution for the WASM tcp built-ins

`rontolisp:tcp-connect` / `tcp-listen` accept hostnames on the interpreter/JVM
(`java.net.Socket` / `InetAddress` resolve natively) but only IPv4 literals on the
WASM component backend: `adapter-sockets.wat` parses the dotted quad itself
(`$parse_ipv4`) and returns errno 28 for anything else.

To lift that, wire `wasi:sockets/ip-name-lookup@0.3.0` (already vendored in
`src/wasm-component/deps/sockets/ip-name-lookup.wit`; wasmtime hosts it behind
`-S allow-ip-name-lookup=y`): import it in `uni-sockets.wit` (appended last to keep
instance indices), lower `resolve-addresses` (returns a `stream<ip-address>` — a
third element-typed stream needing its own `stream.read`/`drop-readable` built-ins,
like the accept stream), read the first address in the adapter's `$tcp_connect`
when `$parse_ipv4` fails, and regenerate the blobs + re-derive the
`WasmComponentBuilder.buildSock` constants. The core `"sock"` seam (a raw
hostPtr/hostLen string) already carries the hostname unchanged, so only the
adapter and the component wiring move.
