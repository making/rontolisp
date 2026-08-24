# The Metal half of the launch-pipeline round: the layout by value, and the collector rule

Difficulty: Medium

Filed 2026-08-24, after `.todo/496` and `.todo/498` closed on CUDA. The machine that did
that work has no Metal device, so what of it carries to Apple silicon was reasoned about
and not measured. Read `.kb/gpu.md` "The step is device-bound" (todo-496), "The collector
question" (todo-498) and "Lazy results and the resident tier on Metal" (todo-494) before
starting; the numbers below are CUDA's and are the starting line, not the expectation.

## What of the round does NOT apply here

`.todo/496` removed two host-side serializers. The first -- the post-launch
`cuCtxSynchronize` that lazy results made pointless -- has no Metal counterpart to remove,
because on Metal every call is `commit` + `waitUntilCompleted` by construction. Making
that asynchronous is `.todo/495` and stays there; this item must not start it. Its
safepoint half does not travel either: the CUDA argument is about a `GetPrimitiveArrayCritical`
window holding a copy, and `MetalGemm` stages through `MemorySegment.copy` into a shared
slab, so there is no critical window to reason about. `MetalGemm.lazyResultsPay()` is
`false` today, so the strided path here runs eager per call whatever this item does --
which is why the change below is worth having on its own terms, not as a lazy-mode
follow-on.

`.todo/497` was chapter 2 measured at the book's shapes; `.kb/gpu.md` already carries the
Metal rows for chapter 3, and a 90-minute run on a laptop is not what this item is for.
Out of scope.

## 1. The layout by value

`.todo/496`'s second serializer was the strided tier's 192-byte layout upload: on CUDA a
synchronous `cuMemcpyHtoD` that drained the null-stream queue, so each of the ~1000
`bcast`/`gather`/`copy`/`where` calls a step was a hidden synchronize. It now rides by
value in the kernel parameter block (`strided_meta`, 64 ints at `Gpu.MAX_STRIDED_RANK`);
per step `cuMemcpyHtoD` went 1056 -> 57, allocations 4362 -> 3363, the step 0.77 -> 0.695 s.

**On Metal the copy is not the problem and must not be described as one.** `uploadLayout`
writes the ints straight into a shared slab's `contents()` -- a memcpy on unified memory
that orders behind nothing. What the layout costs here is different and smaller:

- one pooled slab acquired and released per strided call (`call.ensureBytes(...)`), and
  `MIN_SLAB_BYTES` is 4096, so a 96-to-256-byte layout takes a 4 KB slab;
- one `setBuffer:offset:atIndex:` binding of that slab;
- and, the reason it matters beyond the allocation, a pooled buffer that a committed
  command buffer reads. Today that is safe only BECAUSE the call waits. `.todo/495` step 1
  has to give every recycled slab a per-slab fence; a layout that never touches the pool is
  one class of slab that fence never has to cover.

Metal already passes every other small argument block by value -- `setBytes:length:atIndex:`
appears at a dozen call sites in `MetalGemm` -- and the limit for it is 4 KB against a
worst case of 4 stride vectors at rank 16 = 256 bytes. The shader needs no change: a
`constant int* meta [[buffer(N)]]` parameter takes `setBytes` at that index exactly as it
takes a buffer. Verify that on the device rather than trusting this sentence.

**Count before changing.** `MIN_STRIDED_ELEMENTS` is `2^18` on Metal against CUDA's much
lower thresholds, so most of the strided members CUDA sends to the device may never leave
the CPU here. Instrument a book-shape step (the fast-corpus program `.todo/494` used
reproduces the model at a 36456-character corpus) and count the strided launches and the
slab acquisitions they cause. If the count is small the honest outcome of this item is a
recorded number and no change -- say so in `.kb/gpu.md` and close it.

### Do

1. Drop the layout slot from the four strided call sites (`bcastF`, the gather/permuted
   copy, `where`, `copy_strided`): no `ensureBytes` for it, write the layout into a
   confined `Arena` segment and `setBytes:length:atIndex:` it at the index `bind` used to
   fill. `bind` binds `slots[k]` to index `k`, so the layout slot has to leave both.
   `take` and `scatter` keep their index buffers, whose size is not fixed -- the same line
   CUDA drew.
2. Keep the rank ceiling explicit: assert the layout is at most `4 * MAX_STRIDED_RANK` ints
   and that `Gpu` is what enforces the rank, so the `setBytes` length can never grow past
   the 4 KB limit unnoticed.
3. Measure: the strided launch count and slab acquisitions per step before and after, the
   `MtlResidentFloor` per-call floor, then the notebook's shapes (0.102-0.104 s a step, the
   method in `.kb/gpu.md`) and the book's (8.9 s a step) on the pure pool. Record all of
   them beside the round's numbers whichever way they come out.
4. Bit-identity is the gate: every output byte-identical to the previous build's, the
   `MetalGpuTest` strided pins unchanged and green. The layout the kernel indexes out of
   is the same ints in the same order; if anything moves, the change is wrong.

## 2. The collector rule on Apple silicon

`.todo/498` settled on CUDA that the two collector rows the README recorded are made of
pages the device has never touched, not of collection work (a full collection is 50 ms
under either collector; total pause is 3% of the run), and that no flag wins at both
shapes -- so the README now prints a rule: name a young generation only for a shape the
program fills, otherwise leave the collector alone and add
`-XX:+ExplicitGCInvokesConcurrent` to a long run. `-XX:+AlwaysPreTouch` is recorded there
as a trap under G1 and a win under ParallelGC at the notebook's width.

`DeviceResidency` is shared -- its `collectionWanted` / `COLLECTION_SHARE` request runs
`System.gc()` on Metal too -- but every number under that rule was taken on a GB10 with
CUDA, where an upload is a copy into pages the GPU may never have touched. On Apple
silicon a "device copy" is a memcpy into a shared `MTLBuffer` the pool already holds, so
the mechanism the rule is built on may be weaker or absent, and the flags could easily
land somewhere else.

### Do

1. `-Xlog:gc` over the two Metal shapes (the notebook's width, and the book's via the
   fast-corpus program), default collector against `-XX:+UseParallelGC` with a young
   generation sized to the shape, plus `-XX:+ExplicitGCInvokesConcurrent` and
   `-XX:+AlwaysPreTouch` on each. The control that gave the rule its teeth on CUDA was
   `-XX:+DisableExplicitGC` (4.5x slower there): run it, because it is what says whether
   the library's collection request earns its keep on this device at all.
2. Then either add the Metal row under "The collector question" and generalize the rule,
   or scope the existing rule to CUDA in `.kb/gpu.md` and in both guides. One of the two
   has to happen -- the guides currently state it without a device qualifier.
3. If the request does not earn its keep here, that is a `GpuDevice` question (a
   per-device collection policy), not an edit to the shared default. File it rather than
   changing `DeviceResidency` for both devices from one machine's numbers.
