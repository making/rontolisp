# 112. GC component: print inside SYNC exports (fd_write over WASI 0.2) — unify the `:async` story with `--no-gc`

## STATUS: ON HOLD (deliberately deferred 2026-07-11; policy decision pending)

## Problem

The `:async` handling is asymmetric across the two component paths, and the
asymmetry bites exactly on "I just want to print":

- **GC `--component`**: all I/O (print included) flows through the WASI 0.3
  async stream built-ins, which are async-task-only — so `print` inside a
  sync-lifted export **traps at runtime** ("cannot block a synchronous task")
  and the user must write `:async t` (todo-92 Tier 3).
- **`--no-gc --component`**: `print` works inside plain SYNC exports (the
  todo-93 print micro-adapter routes the single `fd_write` import over WASI
  0.2's synchronous `output-stream.blocking-write-and-flush`), and `:async`
  is a compile error (no async machinery exists).

Same printing export source: GC demands `:async t`, `--no-gc` rejects it.

## Proposed fix (option A, the recommended one)

Port the todo-93 idea back to the GC path: rewire the base/http/sock
adapters' **`fd_write` (fd 1/2) only** from 0.3 `stream.write` to WASI 0.2
`blocking-write-and-flush`. A synchronous host function is legal inside BOTH
a sync lift and an async task, so:

- `print` inside a sync export works on the GC path too — the unified rule
  becomes: **print works in sync exports everywhere; `:async t` is needed
  only for I/O that `--no-gc` can never do anyway (fetch/read/files/stdin)**.
- Bonus: a print-only export becomes callable from **jco** (jco 1.25.2
  cannot call stackful-async exports — upstream callback-ABI gap — so today a
  GC export that prints is wasmtime-only).

Feasibility is already proven in production: the serve preview1 bridge
(`adapter-serve-p1.wat`) prints inside sync `handle` calls exactly this way,
and the fetch variant is already a 0.2/0.3 hybrid. No shim/fixup cycle here —
the GC component instantiates the shared mem module first, so the adapter can
take the lowered 0.2 funcs directly.

## Costs / why it is on hold

- `uni.wit`/`uni-http.wit`/`uni-sock.wit` gain `wasi:cli/{stdout,stderr}@0.2`
  + `wasi:io@0.2` → **import-block + adapter blob regen for all three
  variants**, re-derive every wiring constant in `WasmComponentBuilder`, and
  **every existing GC component changes bytes**.
- Dilutes the "base component is pure WASI 0.3" story (the strategic
  stepping stone toward language-level async, `.todo/02` lineage) — though
  fetch already compromised purity with `wasi:http@0.2`.
- `fd_read`/`path_open`/stdin stay on 0.3 streams, so read/file I/O inside a
  sync export still traps — the step from "print OK" to "read needs
  `:async`" remains (but that mirrors `--no-gc`'s "print only" shape, so
  consistency still improves).

## Rejected alternatives

- `--no-gc` accepting `:async` as a warning no-op: silently-sync lies about
  semantics; the clear error (todo-93) is better.
- Auto-detecting I/O and auto-flipping exports async on GC: already rejected
  in todo-92 Tier 3 (funcall/apply reach everything through the arity
  dispatchers; would break sync byte-identity unpredictably).
- Wait for upstream only (jco stackful async, wasmtime default flags):
  reduces the `:async` friction but leaves the asymmetry.

## Related

- `.todo/93` print micro-adapter record (the `--no-gc` half, DONE 2026-07-11)
- `.todo/92` (`:async t` stackful lift; jco gap documented there)
- `.kb/wasi-component.md`, `.kb/no-gc-scalar-wasm.md`
- `src/wasm-component/adapter-serve-p1.wat` (the 0.2 sync-write precedent)
