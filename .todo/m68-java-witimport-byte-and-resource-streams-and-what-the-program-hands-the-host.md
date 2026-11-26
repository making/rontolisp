# m68. Java @WitImport: a byte stream, a stream of resources, and what the program hands the host

Difficulty: Medium

Left by m55 (2026-11-26; `.kb/java-frontend.md`, "WIT imports", "What the host answers in
pieces"). A stream of values and a future the host answers are a `WitStream<T>` and a
`WitFuture<T>`; three kinds stay refused at the method:

- **A `stream<u8>`** (`wasi:http` bodies, `wasi:sockets`' `receive`, `wasi:filesystem`'s
  `read-via-stream`): an `InputStream` is its Java type, over the alias read the Lisp side
  binds (a packed octet vector a read; under asyncMode a pending future the binding forces).
- **A stream of resources** (`wasi:sockets`' `listen`): its alias read answers one handle a
  read.
- **A stream or a future the program hands the host** (`send: func(data: stream<u8>)`,
  `consume-body`'s `res: future<...>`): the binding would make the pair (`-new`) and write
  (`-write`); the Lisp side binds a write for a byte stream and a future, not for a stream of
  values.

Decide each from what the Lisp side binds, as m55 did, and pin it on a host that answers it
(wasmtime's sockets or HTTP types, as `JavaWitImportTest` does).
