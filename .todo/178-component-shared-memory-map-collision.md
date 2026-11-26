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
the call. Two ways something could be, both only while the wrapper WAITS inside its bracket
(`emitBlockedWait`: a future read, a stream write, a read outside asyncMode):

1. The host completes another subtask during the wait and lowers its result through
   `cabi_realloc` (the canonical ABI lowers at the callee's return, not when the guest
   takes the event); the scheduler lifts it after the wrapper has popped it.
2. The host runs another task of the instance during the wait (wasmtime serve runs up to
   16 requests of a p3 instance at once), whose permanent allocations -- an async start's
   staging, a free-listed read buffer -- land above the mark.

First measure whether either happens on wasmtime 51 and Spin 4.3.0 (a probe: one request
blocked in a future read while another runs; an async call completing during a blocking
wait). If one does, the fix is a mark per waiting bracket that no pop crosses, or an
allocator that frees what it lifted instead of popping.
