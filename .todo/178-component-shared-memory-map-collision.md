# The `--component` shared memory has three writers with overlapping regions

Found 2026-07-26 while getting cl-postgres onto the component (`.todo/115`,
`.todo/177`). A fix was attempted and REVERTED in the same session; this file
records what is true, what was tried, and why it failed, so the next attempt
starts from the measurements instead of the symptom.

## The collision

A component's ONE linear memory is written by three parties:

| writer | region | growth |
|---|---|---|
| mem module's `cabi_realloc` (`src/wasm-component/mem.wat`) | from 0x10000 up | monotonic bump, never freed |
| the adapter's scratch (`src/wasm-component/adapter.wat`) | page 5: 0x50000.. (env/preopen buffers, stream/future handle cells, 64-slot fd table at 0x50100) | fixed cells |
| the rontolisp core: static data, runtime intern table, bump heap | from `DATA_BASE_OFFSET` = 256 up | grows with the program |

The core's static data alone is 3.0 MB for cl-postgres, so it covers the ABI
heap's base, the core's own env buffers (0x30000/0x34000), the socket cell
(0x40018) AND the adapter's page-5 scratch. Independently, the ABI bump only
ever grows, so past ~256 KB of cumulative canonical-ABI traffic it walks into
page 5 itself.

Nothing enforces the separation. Programs that work today work because the
bytes each side clobbers are not re-read afterwards: an interned string that
is never printed again, an adapter cell rewritten before its next use.
cl-postgres is where it finally surfaced -- an adapter handle cell read back
garbage mid-connection and wasmtime reported `unknown handle index` from
inside `fd_write`, which reads like an async/scheduler bug.

## What was tried, and why it was reverted

Two changes together:

1. `COMPONENT_DATA_BASE_OFFSET = 0x60000` -- the core's static data starts at
   page 6 under `--component` only (Preview 1 keeps 256 and stays
   byte-identical), so the core no longer overlaps anything.
2. `cabi_realloc` WRAPS at the top of a fixed window instead of growing
   forever -- a ring bump, on the premise that every canonical-ABI allocation
   is per-call transient.

(1) is sound as far as it goes and fixed the "unknown handle index".
**(2)'s premise is false**, which the ci-spec corpus proved immediately: the
adapter CACHES pointers into canonical-ABI allocations across calls (its
preopen descriptor cache and the environ/get-directories lists are the ones to
look at first), so a wrap recycles memory that is still live. The corpus --
which prints ~1500 lines, so it does a lot of ABI traffic -- then trapped in a
plain `(read-byte in nil -1)` at EOF, long after the wrap. Reverted both
(keeping the change set's other, independent fixes) so the tree stays green.

## What a real fix has to answer

- **Which canonical-ABI allocations does the adapter retain past the call that
  made them?** Enumerate them in `adapter.wat` (the cached preopen at
  0x50040, the environ list at 0x50020, get-directories at 0x50030 are the
  candidates). Either copy them into adapter scratch at capture time -- then
  the ring premise becomes true -- or exclude them from any reclamation.
- **Two bump allocators grow in one memory.** Even with (1), the core's heap
  and the ABI heap both grow; today they only avoid each other by luck of
  size. Options: grow toward each other from opposite ends with a checked
  meeting point; give the ABI heap a per-call reset like the serve path
  already does (`CABI_HP_CELL_ADDR`, driven from the `handle` wrapper) and
  extend that to `run`; or make `cabi_realloc` grow the memory.
- **The regression gate is the ci-spec corpus on the component**, not a small
  program: run the native `CiSpecE2eTest` (`.kb`/CLAUDE.md's native E2E
  procedure), which is what caught the ring.

## Why it is not urgent

No user-visible failure is known that this alone unblocks: cl-postgres on the
component is blocked behind `.todo/177` (a socket read that never settles)
regardless, and every other component program in the test suite passes. It is
a latent-corruption risk that grows with program size -- worth fixing before
the next multi-megabyte component library lands, not before.
