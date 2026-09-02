# Measurement: is the number answering the question you asked?

Two rules, from two different ways the answer comes back wrong. The first is about
a number that does not mean what it looks like; the second is about measuring the
wrong thing at all -- building a proposal in order to price it, when its CEILING
could have been priced without building it.

## Rule 1: a probe's shape is not the step's shape

Almost every acceleration number in this repository was taken by an ISOLATED PROBE: a
small program that calls one member N times at the workload's shapes and divides, because
an end-to-end wall swings several percent and a member worth a few hundred microseconds
cannot be seen in it. That instrument answers **"what does this member cost"**. It does
not answer **"what does this member cost in the workload"**, and the gap between the two
is not noise: a probe decides several things FOR ITSELF that the workload decides
differently -- whether the operand is already on the device, whether the pool's threads
are awake, whether the clocks are up, whether anyone waits for the result. Each of those
has already produced a wrong conclusion here, and every one of them was caught only by
measuring again in the real program.

**This file exists because the rule does not belong to any one backend, and putting it in
a backend's file has already failed once.** `.todo/478` found the thread-pool form of it
in 2026, called it "probe 6, the decisive one", and wrote it down with its mechanism, its
numbers and its measuring conditions -- in `.kb/simd-parallel.md`, under `--parallel`.
Two rounds later `.todo/649`, working on `--blas`, re-derived the same trap from scratch
against the same mechanism at the same order of magnitude. Nothing was missing from the
record except its ADDRESS. So: the rules live here, backend-neutral; the mechanisms and
the numbers stay in the file that owns each one, and this file only names the general
form and points.

### What to do about it

**A probe proposes, the workload disposes.** An isolated number is a HYPOTHESIS about the
workload. Land it after the workload -- or a structural count taken inside the workload --
has agreed with it. A structural count is safe where a wall is not, because it counts
events instead of timing the host: nsys's `cuMemcpyHtoD` / `cuCtxSynchronize` / device
kernel time, a download or command-buffer counter, a launch census. It is the per-call
WALL that lies.

**When you take a probe number, check it against the traps below and record beside the
number that you did.** This makes the numbers reachable FROM THE RULES: a table that says
which traps it was checked against can be re-read when a trap turns out to have a fifth
form, and one that does not, cannot.

**Record enough of the CONDITIONS that someone can adjudicate the traps later without
re-measuring: the thread count, the gap between calls, whether the result was forced
home.** This is a separate rule from where the number lives, and the two fail separately.
`.todo/478` wrote its conditions down in full ("probe 6", "back to back", "a 20-200 us
gap") -- which is exactly why it can be read TODAY as the first instance of trap 1 -- and
still did not reach the next person, because it lived under `--parallel`. `.todo/471`'s
Accelerate column has the opposite gap: it is in the right file and it NAMES the
thread-pool trap in the sentence after its own table ("The 64-thread column is the trap"),
but while its dorian rows say "1 thread" and "64 threads", its M4 Max rows do not say
anything, and Accelerate decides for itself how many threads a problem size gets. So
nobody can now say whether that 6-9x was measured against a pool the probe kept hot or
against no pool at all -- not that it was wrong, that it is UNDECIDABLE short of measuring
it again. Placement without conditions and conditions without placement each lose the
number; you need both.

**A measurement that says the change is not worth its blast radius is a result.** That is
CLAUDE.md's rule and it is the reason the traps matter: a probe that flatters a change
gets it landed, and a probe that flatters the status quo gets it declined. Both are
wrong in the same way.

### The traps, and where each one's mechanism is written down

**1. The pool's workers never go idle in a back-to-back loop.** A probe that calls in a
tight loop keeps every worker thread hot, so no dispatch pays the unpark chain the real
caller pays between calls. `.todo/478`, `.kb/simd-parallel.md` ("Back-to-back calls are
not the workload"): 768x288 measured 1.8x back to back and 0.5-0.9x with a 20-200 us gap
between calls -- and the in-situ result was the one that matched the end-to-end tok/s.
`.todo/649` hit the same shape through OpenBLAS's own pool, `.kb/linalg-blas.md`: the
288x288 `#f` gemv is 17.4 us hot and 90.0 us with ~200 us of unrelated work between calls,
against 13.3 us capped to one thread. Those are x86 numbers; **the Apple side of that one
is not measured yet** (`.todo/651`). Note what the two items are between them: the FIX
`.todo/478` chose is workers that spin on an epoch and park only after 1 ms idle,
"what every BLAS does" -- so the property that makes a tuned pool fast is exactly the
property that makes a back-to-back probe of it lie. The trap and the optimization are one
mechanism, which is why finding the optimization is not the same as being safe from the
trap.

**2. What the probe times may be the ENQUEUE, not the work.** Under lazy results with
asynchronous submission an accepted member returns before the device has run it, so its
wall is the submission. `.kb/gpu.md`, "Asynchronous command buffers on Metal": the SAME
todo-643 probe, not one line of implementation changed, read 8.02 / 1.22 ms before that
item and 8.32 / 0.017 ms after it. The discipline where there is no profiler is to print
BOTH columns -- one forcing every result home with a single element read, one not -- since
a member that ran on the host costs the same in both, which is what makes the pair
readable; `.todo/123-gpu-acceleration/mtl-where-mask-width.lisp` and
`mtl-layer-norm-affine.lisp` are shaped that way. This is worst for a change that REMOVES
A SYNCHRONISATION POINT, because on the enqueue column a round trip that is gone and one
that merely moved behind the asynchrony look identical.

**3. What the probe holds may be RESIDENT when the workload's is not.** A probe's operands
are usually built once and reached every repetition, so a cache that adopts on the second
sight has them from the third repetition on; a workload's intermediates are new every
iteration and never get there. `.kb/gpu.md`, "What the fold's SHAPE decline costs on this
backend": a probe of exactly that shape said the host round trip was gone, and the real
model still paid 192 of them a step, the count matching the declines exactly. (`5baaf6ec`
has since removed those 192, re-measured on Metal 2026-09-03 in that same section -- so the
trap is in the probe having declared them absent while they were there, not in their still
being there today.)

**4. What the probe leaves behind may be the wrong CLOCKS.** A member that runs on the
host leaves a gap, and a device that idles through it drops its clocks, so the next
accepted call is not the call a back-to-back probe timed. `.kb/gpu.md`, "The map threshold
at the straddling shape" -- which also records that this one is Metal's and does NOT
transfer: CUDA measured flat to within 1% out to a 32 ms gap.

**Not every threaded number is trap 1, and the ratios do not compare across items.**
`.todo/478`'s 0.5-0.9x is parallel GEMV against SERIAL -- the cost of waking the pool --
and `.todo/471`'s 6-9x is Accelerate against the `FloatVector` LANE KERNEL. Different
quantities, so the one being under 1 and the other being 7 is not a contradiction, and
todo-478's own conclusion is that a tuned BLAS beats the parallel GEMM. Before citing a
ratio as evidence for or against a trap, check what its denominator was.

Trap 1 and trap 4 are the same sentence about two different resources, which is the best
argument that a fifth form exists and has not been named yet. When you find it, add it
here and leave its numbers where they were measured.

**"We did not hit any of these" is a claim that has to be CHECKED, not assumed.** todo-650
did it the right way for the enqueue trap: it compiled a variant with the host read taken
out, profiled both, and showed the copy and synchronisation counts identical to the event
(292/302/204 against 4/14/12, unchanged) -- so the absence was measured rather than
argued. A per-call table that says nothing about which traps were considered is not
evidence that none applied.

## Rule 2: price the CEILING of a proposal before building it

**Measure the most the idea could possibly be worth. If the ceiling is not significant,
the implementation is not needed, and the measurement is the deliverable.** This is
CLAUDE.md's "a change that measurement says is not worth its blast radius is a result;
land the measurement, not the change" turned into a procedure: the point is to find the
bound BEFORE writing the thing, because once it is written the pull to land it is real and
the comparison is no longer free.

The ceiling is usually reachable by a cheat that would be unacceptable to ship. Two items
closed this way without an implementation:

- `.todo/635` (`.kb/gpu.md`, "The last-axis fold's tiling"): the tiled last-axis fold wins
  1.33-1.96x as a KERNEL, which sounds decisive -- and then the census of the workload
  found that of 864 fold calls at the book's shapes, `inner == 1` happens ZERO times,
  every one being an axis-0 fold whose lanes are already coalesced. The ceiling was a
  speedup on no call sites.
- `.todo/650` (`.kb/gpu.md`): rather than widen the fused softmax's accept rule and then
  measure, it FORCED every one of the 144 heads to be accepted (`WIDEN=1`, materializing
  the mask to the score's shape) and measured that. 0.06 s over 13 steps, 0.8%, inside the
  noise. The accept rule was left alone.

And one that shows the cost of skipping it: `.todo/646` (`.kb/gpu.md`, "Layer-norm's
affine on Metal") wrote the kernels first. They were correct and bit-identical on the first
run and a quarter faster per call, and the step did not move, so they were not kept -- the
per-call table that decided it against the change is exactly the ceiling that could have
been taken first, against the chain the decline already ran.

Note what a ceiling does NOT excuse: it is still a measurement, so rule 1 applies to it.
todo-650's ceiling was taken in the model with a structural count, not in a probe.

## Rule 3: an A/B whose baseline moved is not an A/B

**When several sessions push to `develop`, the arm you are not changing can be changed
under you, and the ratio you are reading is then partly someone else's.** This is not
rule 1 -- the probe's shape can be perfect -- and it is not the semantic conflict CLAUDE.md
warns about either, which is two sides touching one mechanism. Here only ONE side is
touched, by a third party, and `git merge` has nothing to say about it.

`.todo/646` (`.kb/gpu.md`, "Layer-norm's affine on Metal") measured the fused pair at 9.2%
over the decline, every round winning, and it was wrong by 5x: `5baaf6ec` (todo-650's
materialize fix) landed mid-run and pushed the DECLINE arm from 1.798 to 1.680 s, because
the decline is the arm that runs the host chain the fix stopped materializing for. The
fused arm was untouched, so the gap opened on its own. Re-measured on one tree, the pair
is a coin flip (1.680 vs 1.702 s) and the kernels were dropped.

**What to do about it**: take both arms from ONE tree, and re-take the baseline after any
merge that lands between the two halves of a comparison -- a merge is cheap to do and
invisible in the numbers. If a run spans a merge, say which commit each arm was built at,
so the next reader can tell whether the arms are comparable at all (rule 1's condition
requirement, applied to time rather than to setup).
