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
stream-table entries.

On the component backend the WIT side needs nothing:
`src/main/resources/am/ik/rontolisp/eval/sockets.wit` already vendors the full
`udp-socket` resource (`create` at line 602, `send`/`receive` at 726/752). The
work is a Lisp-source addition to
`src/main/resources/am/ik/rontolisp/eval/sockets.lisp` (spliced by
`eval/SocketsLibrary`) over that bound resource, following the `%sock-*` idiom.
Datagram `send`/`receive` are `async func`s, so their call sites need
`rontolisp:await` promotion in `codegen/wasm/WasmSocketsRewrite`, like
`tcp-connect` / `tcp-accept`. wasmtime gates UDP with `-S udp=y`.
