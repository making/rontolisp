# Asynchronous command buffers on Metal, so that lazy results can pay there

Difficulty: High

Filed 2026-08-23 when `.todo/494` closed. Read `.kb/gpu.md` "Lazy results and the resident
tier on Metal" first. That round built lazy results and the resident tier on Metal,
bit-identical and pinned, measured them on `train-gpt-soseki` (M4 Max, JVM class output,
`--gpu --simd`), and kept the interceptors EAGER there (`MetalGemm.lazyResultsPay()` is
`false`, `Gpu.lazyResultsIfWorthwhile` is what the interceptors call): a tie at the
notebook's shapes (0.102 against 0.104 s a step) and a loss at the book's (10-19 s a step
against a steady 8.9). The first of the three reasons it gave is the one this item is for:

**every Metal call is `commit` + `waitUntilCompleted`.** Nothing overlaps. On CUDA the
launches are asynchronous and the host's autograd bookkeeping, allocation and host-side
members run under them; on Metal the step is the CPU's time PLUS the device's, so a member
moved from the CPU to the device pays in full and wins only if the device is outright faster
at it -- and at 6-25 M elements a memory-bound launch at the ~80-150 GB/s the shipped route
reaches (a 25 M-element `zip` is 2.2-3.6 ms) is not much faster than the M4's lane loop
over the same bytes. The other two reasons (the saved download is a memcpy on unified
memory; a resident set of tens of gigabytes beside the heap puts the machine under memory
pressure, `.todo/492`) are not this item, but the second gets smaller with the first: a
device that runs behind the host is not the bottleneck the host waits on.

The per-call floor is the same fact at small shapes: a resident launch through the shipped
route is ~100-140 us whatever its size until 2^18 elements (`MtlResidentFloor.java`), of
which ~77 us is the command buffer's wait. A chain of ~190 members a step at the notebook's
shapes is ~20 ms of waiting however little it moves.

## Do

1. Stop waiting per call. Commit each member's command buffer and return; wait only
   where the host is about to touch bytes the device may still be writing or reading:
   - `materialize` / the drain's flushes / an eager download: wait for the command
     buffer that last WROTE the slab;
   - `stage`'s upload into a slab taken from the free list, and `take`'s reuse of any
     slab: wait for the command buffer that last READ or wrote it -- the one ordering
     `DeviceResidency`'s design exists to forbid is a slab recycled under a launch that
     still reads it (today the end-of-call drain is safe only BECAUSE the call waited);
   - `written` (a dirty copy comes home first) and `releaseResident`;
   - and `lazyResults(false)`.
   One command queue executes in order, so "the latest committed command buffer" is a
   sufficient fence everywhere; a per-slab "last command buffer" (kept on `Slab`, a
   retained `MTLCommandBuffer` whose `status` is polled before `waitUntilCompleted`) is the
   precise one and is what lets a chain over resident operands never wait at all while the
   host keeps going. Start with the global fence, measure, then refine.
2. Keep the decline protocol intact: a command buffer that ends in any status but
   `Completed` is today an ordinary per-call decline, with `c` untouched. Asynchronously
   the failure is learned AFTER the call answered `true`; the lazy mode already has the
   one operation that cannot decline (`materialize` throws), and an eager call must still
   either fill `c` or say `false` -- so eagerly the wait stays (the library's default
   contract: "`out` is filled when the call returns"), and only the LAZY mode goes
   asynchronous, where a failed buffer surfaces at the first host touch as the
   `IllegalStateException` that mode already reserves for a result the host has no other
   copy of. Say so in `Gpu.lazyResults`'s javadoc.
3. The autorelease pool: a command buffer that outlives its call must be retained
   (`retain` / `release` through `MetalDriver`) rather than left to the pool the call
   pops; `MetalGemm` pushes a pool per call today.
4. Measure in the order the round measured: the floor probe first (`MtlResidentFloor`
   -- a chain of resident `zip`s should drop well under 100 us a member once nothing
   waits), then the notebook's shapes against the pure pool (0.104 s a step, the method
   in `.kb/gpu.md`), then the book's (8.9 s a step;
   `.todo/123-gpu-acceleration/gpt-book-shapes-fast.lisp` reproduces the model at a
   36456-character corpus of the same vocabulary). Flip
   `MetalGemm.lazyResultsPay()` to `true` only if both are faster than the pure pool;
   otherwise record the numbers beside the round's and keep it `false`. Either way the
   interceptors' request (`Gpu.lazyResultsIfWorthwhile`) is the only switch.
5. Re-run todo-509's collector matrix (`.kb/gpu.md`, "The collector, and the flags that do and do not help"). It
   found `System.gc()` called ZERO times on this backend in every configuration at both
   shapes, because the library's collection request is gated on the LRU having only DIRTY
   copies left and eagerly there are none -- so the whole CUDA collector rule is scoped
   away from Apple silicon today. Flipping `lazyResultsPay` starts that request firing
   here for the first time, and the eight-row table has to be re-taken before the guides'
   Apple sentence stands.
6. Tests: `MetalGpuTest`'s lazy tests hold as they are (they read through `materialize`);
   add the ordering pin -- a chain whose result slab is recycled and re-uploaded before
   the host reads the result, asserted to still read the right bytes -- and the failure
   pin of step 2. `aRunOfCallsSettlesTheBufferPoolRatherThanGrowingIt` must still settle
   with command buffers in flight.

## Out of scope

`.todo/492` (no host array for a lazy result) -- the memory half of the Metal loss -- and
`.todo/493`; both apply on Metal once the mode pays there.
