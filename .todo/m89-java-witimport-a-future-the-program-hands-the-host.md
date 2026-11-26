# m89. Java @WitImport: a future the program hands the host, written when the host reads it

Difficulty: High

Left by m68 (2026-11-26; `.kb/java-frontend.md`, "What the program hands the host"). A
`future<T>` parameter is refused at the method: the binding could write it only after the
call, and the Lisp side's `-write` waits until the reader takes the value. Measured
2026-11-26, wasmtime 51: a write of `wasi:http/types@0.3.0`'s `request.new` trailers future
right after `request.new` never returns -- the host reads it within `client.send`, an async
func the Java subset waits on synchronously (`:blocking t`). The same holds for
`consume-body`'s `res` once the program has a response.

What would open `wasi:http`'s client to a Java program:

- A future write that does not wait: the async `future.write` left pending when BLOCKED,
  the lowered payload kept past the wrapper's staging pop (a permanent allocation, as the
  asyncMode stream read's chunk buffer is), the writable end dropped once the write has
  completed (or leaked: measure what wasmtime does with a pending write at exit, and with
  one handle a request in a long-running program).
- Its Java type: candidates `WitFuture<T>` the program made (`WitFuture.of(value)`), written
  from `get()` right after the call; or a type the program completes later. Decide from what
  a program needs between the calls (`request.new`, `client.send`, `consume-body`).
- A request body (`request.new`'s `contents: option<stream<u8>>`) cannot be written while
  `client.send` blocks: a `stream<u8>` held in another type is refused today too. A GET needs
  only the futures; a body needs `client.send` to run while the program writes, which the
  blocking lowering does not allow. Say which a first version covers.

Pin on wasmtime's HTTP types against a `wasmtime serve` backend, as
`WasmLispCompilerIntegrationTest#componentImportDropsResourcesSoALispRequestCanCarryABody`
does for Lisp.
