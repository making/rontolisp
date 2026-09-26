# A component stream read ignores the end a DROPPED completion delivers with its items

Difficulty: Medium

The component model packs a stream read's result as `(count << 4) | code`, and DROPPED takes
precedence over COMPLETED (CanonicalABI.md, `StreamEnd.notify`): one read can deliver its last
items and the writer's drop together, `Dropped(n)` with `n > 0`. The end is then DONE, and another
`stream.read` traps ("cannot read after being notified that the writable end dropped"). The
byte-stream read wrapper (`WasmComponentImportCompiler.emitStreamRead`) and the scheduler's
completion (`WasmFutureRuntimeBuilder.buildSchedDispatch`) look at the count alone: `n > 0` is a
chunk, `n = 0` the end. A `Dropped(n)` is answered as a chunk with the end not latched, so the
consumer's next read traps. The consumers are http.lisp's bodies, sockets.lisp and stdin.lisp.

The hosts the tests run never deliver it (checked 2026-09-26 against wasmtime 49.0.0's sources):
wasi-http's body producer, the TCP receive stream, stdin and the filesystem read stream all end
with `Dropped(0)`. wasmtime's generic `Vec` / `Bytes` producers (`set_buffer`, then `Dropped`) are
the ones that answer `Dropped(n)`.

## Goal

A `Dropped(n > 0)` completion answers its chunk and latches the end for that handle, so the next
read answers nil without touching it; the latch dies with the handle (`drop-readable`), because
handle numbers are reused. A test needs a producer of that shape -- an intra-component
`stream<u8>` whose writer drops right after a partial copy, if wasmtime reports that as
`Dropped(n)`, or a host stub.
