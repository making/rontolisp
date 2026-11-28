# A pop of the component's bump heap may free what another task or subtask holds

Difficulty: High

NARROWED 2026-11-26: the served component's own canonical-ABI window (0x10000, reset at the
top of every `handle`) is gone; every component's `cabi_realloc` bumps the core's HEAP_PTR
and grows the memory (`.kb/wasi-component.md`, "Shared-memory map"). Both issues this file
described went with it: a request lowering more than ~320 KB overwrote static data, and the
per-request reset recycled the buffers of a request still running on the same instance.

## What remains (not measured)

A `%component-import` wrapper pops HEAP_PTR back to the mark it took at its start. That is
right for what its own call allocated, wrong for anything allocated meanwhile that outlives
the call. Three ways something could be, the first two only while the wrapper WAITS inside
its bracket (`emitBlockedWait`: a future read, a stream write, a read outside asyncMode):

1. The host completes another subtask during the wait and lowers its result through
   `cabi_realloc` (the canonical ABI lowers at the callee's return, not when the guest
   takes the event); the scheduler lifts it after the wrapper has popped it. So does a
   `<member>-wait` (`.kb/wit.md`): a started `client.send` answering an error with a string
   while a body write waits.
2. The host runs another task of the instance during the wait (wasmtime serve runs up to
   16 requests of a p3 instance at once), whose permanent allocations -- an async start's
   staging, a free-listed read buffer -- land above the mark.

3. MEASURED 2026-11-26 (wasmtime 51, a `bindgen!` host and a composed caller): the host runs
   a SYNC-typed export of the instance while an async-typed export's task waits
   (`.kb/wasi-component.md`, "`:async t` I/O exports"). The export side's own mark is safe
   since then (an async export holds it in locals). Not measured: whether a waiting call
   holds anything the call made meanwhile can write over -- bytes staged past the heap top
   (where a flat `:string` result is staged, though never across a wait), the adapter's
   page-5 cells, which are one set for both calls.

First measure whether 1 or 2 happens on wasmtime 51 and Spin 4.3.0 (a probe: one request
blocked in a future read while another runs; an async call completing during a blocking
wait), and whether 3 reaches a blocked write (a host whose stdout accepts late, an async
export printing a long string, a sync export called meanwhile). If one does, the fix is a
mark per waiting bracket that no pop crosses, or an allocator that frees what it lifted
instead of popping.
