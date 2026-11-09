# Plain TCP writes on the interpreter, JVM and `--native` may stall on delayed ACKs

Difficulty: Medium

The WASM component buffers socket writes since 2026-11-09 (`.kb/tcp-sockets.md`, "A connected socket
BUFFERS its writes"): written one by one under the host's Nagle, each piece of a cl-postgres message
waited for PostgreSQL's delayed ACK of the one before (Shiori `/books` 0.47s against 0.025s).

The other three backends still write through with Nagle on: the interpreter (`SocketSupport`, the raw
`Socket.getOutputStream()`), the JVM (`JvmSocketRuntimeBuilder`, same) and the `--native` runner
(`rontolisp-native/runner/src/tcp.rs`, `write_all`, no `set_nodelay`). Not measured.

## What to do

Measure cl-postgres (or Shiori) against PostgreSQL on Linux on each of the three. If the stall shows,
choose between `TCP_NODELAY` (`setTcpNoDelay`, `set_nodelay`; every write still one segment) and a
buffer flushed at the component's points, and pin it on every backend that changes.
