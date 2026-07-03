# UDP sockets

The `rontolisp:tcp-*` built-ins (see `.kb/tcp-sockets.md`) cover TCP only. WASI 0.3
sockets also defines a `udp-socket` resource (`create`/`bind`/`connect`/`disconnect`,
datagram `send`/`receive` with `list<u8>` payloads and per-datagram peer addresses),
already vendored in `src/wasm-component/deps/sockets/types.wit`, and the JDK has
`java.net.DatagramSocket` for the interpreter/JVM.

Sketch: `rontolisp:udp-open` (bind, optional port) returning a handle,
`rontolisp:udp-send` (handle host port string/bytes) and `rontolisp:udp-receive`
(handle) returning `(payload host port)`. Datagrams do not fit the line-oriented
stream built-ins, so unlike TCP these would be dedicated functions rather than
stream-table entries. The component adapter needs the lowered udp functions plus
nothing new structurally (`wasi:sockets/types@0.3.0` is already imported by the
sockets variant); wasmtime gates UDP with `-S udp=y`.
