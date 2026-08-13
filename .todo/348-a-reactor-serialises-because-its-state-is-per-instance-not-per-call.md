# A reactor serialises because its state is per-instance, not per-call

Difficulty: High

**Not urgent, and nothing is broken.** A serialising host is CORRECT today --
the module refuses overlap with a trap rather than corrupting two calls. This
item is about what that correctness costs, and about the fact that the
mechanism to lift it already exists on the host side and is blocked only by
state that lives in the wrong place. Pick it up after the items already in
flight; `.todo/341` and `.todo/347` name it as their trigger.

Measured cost today: `examples/cloudflare-workers/dog-fetcher`, whose glue puts
every call on ONE promise queue, answers **eight concurrent upstream round trips
about 250 ms apart** (`.kb/wasm-import.md`). They are I/O, and they could have
been one round trip wide.

## Why this is not a wiring gap

Two things a reader will reach for first, and neither works:

- **`--component` does not help a reactor.** `--component --no-wasi` is NOT
  asyncMode (`WasmExportCompiler.java`, "Outside asyncMode (Preview 1,
  --no-wasi, the reactor component)"), and it could not be: the component
  scheduler waits on component-model waitables (subtasks, a BLOCKED
  `stream.read`), and a `--no-wasi` module has none. There is nothing to wait
  ON.
- **Threads do not exist there.** Both WASM backends are single-threaded by
  construction and carry no `:thread-support`.

In reactor mode the ONE suspension mechanism is JSPI, and JSPI parks the whole
wasm stack. What matters is that this is already enough: `WebAssembly.promising`
can hold SEVERAL calls in flight into the same instance, each parked on its own
stack. The host can already overlap. The module refuses, because the two
suspended extents would share:

- **the dynamic (special) variable bindings.** A binding saves the module global
  into a temp local and restores it after the body, so two interleaved extents
  read each other's binding back. That is not a hypothesis: todo-337 reproduced
  it on node 24 `--experimental-wasm-jspi` -- two overlapped calls binding the
  same special across the suspend leaked the outer value, the exact
  pre-2026-07-27 JVM bug (`.kb/dynamic-special-variables.md`).
- **the linear-memory arena.** `__ronto_alloc_mark` / `__ronto_alloc_reset` are
  an absolute restore of one heap-pointer global, so the marks are LIFO and
  cannot nest across interleaved requests -- "overlapping them would need a
  per-call allocator scope, not a second mark".

The answer that landed instead was the RE-ENTRY GUARD (todo-337): a `mut i32`
global every export wrapper checks-and-sets, trapping the second entry
(`reentryGuardGlobalIndex`, `WasmExportCompiler.emitReentryGuardStore`). It
re-establishes "one call at a time on one stack" BY the module instead of
assuming it of the host.

## The order, which is not negotiable

`.kb/wasm-import.md` ("Deliberately NOT per-call state") and
`.kb/dynamic-special-variables.md` (its restated re-evaluation trigger) both
state it, and they state it the same way:

1. **The per-task dynamic store lands FIRST.** The shape is the JVM's `_d$`
   hybrid, already written and already proven there: a per-task cell holding the
   innermost binding, a dynamic-first read (`_dget`), a dual-bind that also
   keeps a lexical slot so a closure captures the entry value, a `setq` that
   writes both, and exit restores that survive `return-from`. The wasm port owes
   the same constraint the JVM one honoured: **byte-identical output when
   nothing is let-bound** (`SpecialVarCollector.collectDynamicallyBound` is
   what decides, and over-collection is only a read cost while
   under-collection must stay a compile-time throw, never a silent
   process-global binding).
2. **The per-call allocator scope.** Precedent exists and is small: the
   `:bytes` import wrapper (`.todo/341` Phase 0) already takes a heap mark on
   entry and pops to it on return, which is what makes its pull loop flat. The
   scope needed here is the same idea one level up, at the EXPORT wrapper, and
   it is bounded work because the arena is only boundary staging -- the Lisp
   heap is GC-managed on this backend, so what has to be per-call is the
   `(ptr,len)` staging and the `:bytes` regions, not the program's values.
3. **Only then relax the guard**, and keep it for a module that opts out.

## What else this unblocks, and what it obliges

- `.todo/341` finding 3 settled that the reactor body protocol takes **no
  handle parameter**, and said exactly why: the re-entry guard guarantees one
  call inside the module, so the import is a global cursor and that is safe.
  Relaxing the guard invalidates that argument. `env.readRequestBody`,
  `env.writeResponseBody` (Phase 3) and `.todo/347`'s `env.readResponseBody`
  all need CALL IDENTITY at that point -- adding the handle is part of THIS
  item's cost, not a separate surprise.
- The host obligation lines the build prints change: "serialise the calls"
  becomes conditional on what the module declares.
- `.kb/dynamic-special-variables.md`'s shallow-binding divergence loses the
  precondition the guard restored, which is the whole reason step 1 comes
  first.

## Weigh these before starting

The item should open by measuring, not by building:

- **Multiple instances is available TODAY and needs no compiler change.** One
  instance per in-flight request has independent memory, globals and arena, so
  the guard never fires. It costs `_initialize` per instance (measured 4.5-4.8
  ms cold on the httpbin pair) plus that instance's linear memory. For a Worker
  serving a handful of concurrent requests this may simply be the answer, and
  it is the honest baseline any design here has to beat. Nothing in the repo
  pins an instance POOL today -- the shipped glue keeps one instance and
  replaces it only after a trap.
- **This buys I/O overlap, never CPU parallelism.** One stack runs at a time;
  what overlaps is the parked time. A CPU-bound reactor gains nothing.
- So the trigger is a workload that is I/O-bound AND cannot afford an instance
  per request. Write down which one motivated the work.

## Non-goals

- Threads or parallelism of any kind on a WASM backend.
- `--no-gc`: it has no globals and rejects `defvar`/`declaim` at top level
  outright, so a special can never be declared there.
- The interpreter and the JVM, which already have real threads and a per-thread
  store.

## Gate

- The todo-337 reproduction with its expectation INVERTED: two overlapped calls
  on node 24 `--experimental-wasm-jspi`, each binding the same special across a
  suspend, each reading ITS OWN binding back -- the test that proved the bug
  becomes the test that proves the fix.
- The arena flat across interleaved calls (`memory.buffer.byteLength`
  unchanged), the `.todo/341` finding-2 pin applied to two callers instead of
  one.
- **Byte-identity** for a module that binds no special and for a module that
  keeps the guard, like every previous addition of this kind.
- `dog-fetcher` with its promise queue removed: the eight-round-trip
  measurement above re-taken, with one round trip's width as the target.
