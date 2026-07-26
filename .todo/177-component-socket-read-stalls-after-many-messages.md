# Finish the cl-postgres query round-trip on the WASM component (the socket read stalls after ~17 messages)

Split out of `.todo/115` (whose JVM leg is DONE) on 2026-07-26. Everything up
to this point in the component's PostgreSQL session is byte-for-byte what the
JVM backend sees; the very next read never returns.

## The symptom, exactly

Program: `examples/db/postgres-hello.lisp` (or the equivalent explicit-load
form), compiled `--component`, run against `docker run --rm -p 54329:5432 -e
POSTGRES_HOST_AUTH_METHOD=trust postgres:17-alpine`:

```bash
rontolisp examples/db/postgres-hello.lisp -o pg.wasm --component
wasmtime run -W gc=y -W exceptions=y -S tcp=y -S inherit-network=y pg.wasm
```

The module VALIDATES (`wasm-tools validate -f all`), connects, sends
`startup-message`, and reads:

| message | tag/len | matches JVM |
|---|---|---|
| AuthenticationOk | 82 / 8, code 0 | yes |
| ParameterStatus x14 | 83 / 17..40 | yes, every name+value |
| BackendKeyData | 75 / 12 | yes |
| **ReadyForQuery** | **90 / 5** | **never arrives -- the read hangs** |

Then the process sits forever (killed at 600 s; a 2400 s run also produced
nothing). The JVM backend, on the identical program and server, gets
ReadyForQuery immediately and returns `((42 "hello"))`.

**It is not the driver's `message-case`.** A hand-rolled tag walk over the same
socket (`read-uint1` / `read-uint4` / `read-str` in a `do` loop, no
`message-case` at all) stalls at the same point, after BackendKeyData.

## What is already ruled out (do not re-derive)

Fixed and landed:

- an over-arity funcall calling the neighbouring runtime helper (a 9-argument
  `funcall` silently invoked the next runtime function -> invalid module);
- symbol function designators (`'list-row-reader`) not resolving;
- an ambiguous slot NAME reading through an unrelated package's reader generic.

Attempted and REVERTED -- both are still NEEDED for this todo, and both have a
recorded reason they cannot be done the naive way:

- **The shared-memory map collision** (`.todo/178`): real, and the cause of the
  "unknown handle index" crashes seen while tracing. The attempted fix (move
  the core's data to page 6, ring the canonical-ABI bump) broke the ci-spec
  corpus, because the adapter retains pointers into ABI allocations across
  calls.
- **`read-sequence`/`write-sequence` and 3-arg `(read-byte s nil 0)` reaching
  the socket dispatch.** cl-postgres needs all three on a socket handle
  (`read-bytes`, `write-bytes`, `read-simple-str`); today they compile to the
  NATIVE stream built-ins, which do not know about socket handles. The
  attempted fix -- pre-expanding the sequence ops inside `WasmSocketsRewrite`
  and widening `%io-read-byte` to `(&optional s eof-error-p eof-value)` --
  made the ci-spec corpus trap on a plain `(read-byte in nil -1)` at EOF over
  a FILE handle, in a TOP-LEVEL (async-context) `with-open-file`. A 3-arg read
  is not promoted to an await, so it takes the `%io-*` sync dispatch inside an
  async task; something in that combination traps at corpus scale (a small
  program with the same shape does NOT reproduce). Whoever redoes this must
  handle the async-context case explicitly, and gate on the native
  CiSpecE2eTest, not a unit test.

Also verified sound: byte transparency of the component socket path over all
256 byte values (an echo probe), and the chunk buffer's cursor bookkeeping by
inspection (`%sock-fill` / `%sock-buf-ready` / `%sock-pop-char` keep the buffer
and advance a cursor; nothing discards a partially-consumed chunk).

## The leading hypothesis

The stall is in the async read machinery, not in the Lisp above it, and its
shape -- "N reads work, read N+1 never settles" -- points at RESOURCE
bookkeeping rather than logic. Each `%sock-fill` awaits
`%sock:sock-stream-read`, which lowers to an async subtask with a
waitable/future handle. If those are not dropped per read, the instance
accumulates them until the host stops delivering. The session's other async
findings (`.todo/176`: two promoted reads in one call evaluate in reverse
order; `fd_write` crashing with "unknown handle index" after many interleaved
reads and prints) are consistent with the same area.

Probes to run first, cheapest first:

1. Count reads to the stall by printing a counter inside `%sock-fill`; confirm
   it is a COUNT (does a smaller server response, e.g. fewer
   `ParameterStatus` messages via a trimmed `client_encoding`/`application_name`,
   push the stall further out?). A count-independent stall means it is the
   BackendKeyData boundary, not resource exhaustion.
2. Instrument `%sock-fill` at the WIT boundary: does `sock-stream-read` return
   an empty chunk (which the current code reads as EOF and would answer nil,
   not hang), or does the future never settle?
3. Check whether the read subtask/waitable handles are dropped after each
   `%sock-fill` (`WasmAsyncEmit` / `%subtask-future` / the scheduler's
   waitable-set join+wait), and whether the set grows monotonically.
4. Compare against a Lisp-only loopback peer doing 20+ small writes: if the
   same stall reproduces without PostgreSQL, the repro shrinks to a unit test
   in `WasmLispCompilerIntegrationTest` and PostgreSQL leaves the picture.

## Difficulty

Medium-high, but bounded -- and much better posed than it looks:

- **In its favour**: a deterministic repro; a message-by-message trace that
  already matches the JVM up to the exact failing read; the failure is a
  HANG (no corruption, no engine divergence); and step 4 above plausibly
  shrinks it to a self-contained loopback test with no external server.
- **Against it**: the code involved -- the component async scheduler, the
  wit-lowered `wasi:sockets` stream reads, `%future-force`'s blocking drive --
  is the hardest layer in the tree, and it is the same layer where `.todo/176`
  already shows two independent defects. A fix likely has to reason about
  subtask/waitable lifetimes, not just Lisp.

Estimate: half a day if it is a missing drop (steps 2-3 find it); two days-plus
if the read future genuinely never settles and the scheduler's park/wake path
has to be reworked.

## Acceptance

`examples/db/postgres-hello.lisp` returns `((42 "hello"))` and
`((1) (2) (3))` under `wasmtime run -W gc=y -W exceptions=y -S tcp=y -S
inherit-network=y`, identical to the interpreter and JVM. Both wasm-GC modes
are in scope only for what each can do: Preview 1 has no host socket API, so
the driver is `--component` only there (a compile error on P1 is correct).
TLS stays interpreter/JVM only (`.kb/tcp-sockets.md`), so the plain-TCP auth
ladder (trust/password/md5/SCRAM) is the whole target. Update
`examples/db/README.md` (its `## WASM` section states this limitation today)
and `.todo/115`'s status in the same change.
