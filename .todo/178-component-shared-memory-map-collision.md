# The SERVE component's canonical-ABI window can still collide with core scratch

NARROWED 2026-07-26 (the todo-177 session): the non-serve collision this file
originally described is FIXED --

- the component's interned-string data starts at page 6
  (`COMPONENT_DATA_BASE_OFFSET` = 0x60000), above the adapter's page-5 scratch,
  the serve cabi window and the core's page-3/4 env/socket cells;
- the non-serve `cabi_realloc` (`src/wasm-component/mem.wat`) bumps the core's
  own HEAP_PTR cell (address 84) -- ONE shared monotonic allocator, so there is
  no private ABI region to outgrow and nothing recycles memory the adapter (or
  the scheduler's free-listed read buffers) retains. Full mechanics:
  `.kb/wasi-component.md`.

## What remains: the serve memory module

`mem-http-client.wat` deliberately keeps its own cabi cell/window at 0x10000
(`CABI_HP_CELL_ADDR`, base 0x10008) with a per-request reset from the `handle`
wrapper, because a resident, instance-reusing host (jco / wasmCloud) must
reclaim request buffers per call -- a monotonic HEAP_PTR bump would grow linear
memory by ~one request per call, and the core's snapshot/pop discipline is
per-export-invocation, not per-task.

Two latent issues survive there, both size-gated and none user-visible today:

1. The window grows from 0x10008 toward the core's fixed env scratch at
   0x30000: past ~128 KB of ABI traffic within ONE request (large request
   bodies/headers lifted into guest memory), it clobbers `ENV_PTRS_ADDR` /
   `ENV_BUF_ADDR` and, past 0x40000, the socket scratch cell.
2. The per-request reset (`mem[CABI_HP_CELL] = CABI_HP_BASE` at the top of
   `handle`) assumes one request at a time; under CONCURRENT callback tasks a
   reset while another task's lifted buffers are still live recycles them.

A unification would give serve the same shared-HEAP_PTR allocator plus a
per-task mark/pop keyed to the task record (the callback-async machinery
already has per-task state) -- design work, to be done when a serve workload
actually hits either limit.
