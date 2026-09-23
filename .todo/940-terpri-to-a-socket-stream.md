# `terpri` to a socket stream fails on every backend

Difficulty: Low (one socket arm in each of the three `terpri` implementations, beside
the `write-line`/`write-string` socket arms that already exist)

Found while working `.todo/933` (2026-09-23): `write-line` with `:start`/`:end`
lowers to `write-string` plus `terpri`, so a bounded `write-line` to a socket needs
both halves. The `write-string` half works; the `terpri` half does not:

- Interpreter: `(terpri sock)` signals `not an output stream` (`Environment.emitTo`
  only takes a `Writer` entry; sockets are not writers there).
- JVM: `(terpri sock)` throws `ClassCastException: Socket cannot be cast to Writer`
  (`JvmIoRuntimeBuilder.buildWriteStr` casts the table entry without the socket arm
  `buildWriteLine` has).
- WASM: presumably the same (`WasmSocketsRewrite` has no `terpri` case; not measured).

The plain `(write-line "hello" sock)` keeps its dedicated socket path
(`_sockWriteLine` / `SocketSupport.writeLine`) and is unaffected, and `write-string`
to a socket works on the interpreter and the JVM (measured 2026-09-23). Fixing
`terpri` unblocks the bounded `write-line` to a socket with no change to 933's
lowering.
