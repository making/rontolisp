# n00. Java @WitImport: a stream the host reads only within a later call (an HTTP request body)

Difficulty: High

Left by m89 (2026-11-27; `.kb/java-frontend.md`, "An HTTP client over `wasi:http@0.3.0`"). A
Java program is an HTTP client over `wasi:http@0.3.0` for a request with no body. A body is
`request.new`'s `contents: option<stream<u8>>`, an `Optional<Consumer<OutputStream>>` handed
the writable end once `request.new` has returned. Measured 2026-11-27, wasmtime 51: a
`write` to it before `client.send` never returns (no reader yet; the program hangs), and
`client.send` is an async func the subset waits on (`:blocking t`), so nothing writes while
the host reads. A body that ends at once (`close()` alone) is sent, and its trailers are
then read from the handed future.

What a body needs, to measure first:

- Whether one pending write is enough: the async `stream.write` left BLOCKED before
  `client.send`, as `<alias>-resolve` leaves a future's (`.kb/wit.md`), the bytes kept past
  the wrapper. Does wasmtime answer the response with the writable end still open, where
  `content-length` says the body is whole? The end can be dropped only after the write
  settled, which is after `client.send` returned.
- Else `client.send` not waited on: the call async-lowered and the program writing while
  the subtask runs, which is what the Lisp side does
  (`WasmLispCompilerIntegrationTest#componentImportDropsResourcesSoALispRequestCanCarryABody`).
  In Java that is a method answering before its result is there: decide its type (the
  `WitFuture` of the result, read by `get()`), and what `:blocking t` then means for the
  one member.
- The `OutputStream` the consumer is handed: a buffer flushed when the host reads, or a
  write that waits as today. Say which, and what `flush()` and `close()` do.

Pin on `JavaWitImportTest.everyKindOfValueCrossesWasmtimesOwnInterfaces`' HTTP client: a POST
whose body the backend echoes. Update `doc/{en,ja}/java/wit.md` ("A future the program hands
the host", Limitations) in the same commit.
