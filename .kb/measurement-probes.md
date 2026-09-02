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

**And the count carries the conclusion even when the wall never becomes explicable.** A
structural count is not merely the safer of two instruments; it is what survives a wall
that goes wrong in a way nobody can account for. todo-663 (`.kb/gpu.md`, "Ceiling 3: a
`-1` reshape extent", 2026-09-03) missed its projection in BOTH DIRECTIONS at once -- the
compiled arm came in at -11.4% against a projected -17.1% and the interpreted one at
-37.4% against -25.6% -- and the obvious explanation, a fixed start-up cost, can only
COMPRESS a ratio, so it accounts for one deviation and not the other (over a 22 s run it
is about 2%, with no room to move a ratio twelve points). The conclusion never wavered
anyway, because `cuMemcpyDtoH` -34 and `cuMemcpyHtoD` -44 matched the prediction of
eighty avoided reshapes exactly. **Taking the count looks like waste in every run where
the wall behaves, and is the whole result in the run where it does not.** A measurement
holding only a wall loses its conclusion the moment the wall stops making sense.

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
Accelerate column had the opposite gap: it is in the right file and it NAMES the
thread-pool trap in the sentence after its own table ("The 64-thread column is the trap"),
but while its dorian rows said "1 thread" and "64 threads", its M4 Max rows said nothing,
and Accelerate decides for itself how many threads a problem size gets. So nobody could say
whether that 6-9x was measured against a pool the probe kept hot or against no pool at all
-- not that it was wrong, that it was UNDECIDABLE short of measuring it again. Placement
without conditions and conditions without placement each lose the number; you need both.

**It was decided by measuring it again, and it cost a day** (todo-651, 2026-09-03): the
column was one thread and stands as measured. Note what the missing condition cost --
re-running two probes and an end-to-end model on a machine that had to be quiet -- against
what writing "1 thread" beside the number would have cost when it was taken. **And note
that the answer could not be inferred**: Accelerate exports no thread query at all, so the
count had to be established indirectly, by finding the shapes where
`VECLIB_MAXIMUM_THREADS=1` moves the library's own time. A condition you can still recover
is lucky, not the normal case.

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
against 13.3 us capped to one thread. Those are x86 numbers, and **the Apple side is now
measured too: the trap is not there** (todo-651, 2026-09-03, `.kb/linalg-blas.md`, "What
Accelerate does about threads"). Accelerate runs every shape a decode loop makes on ONE
thread -- `VECLIB_MAXIMUM_THREADS=1` moves nothing from 131 Kflop to 33.6 Mflop -- and at
the shapes where it does thread it pays no wake-up: `dgemv 2048x2048` is 127-132 us back to
back and 124-128 us with the same 200 us gap. End to end the flag is a 1.90x WIN there and
capping it costs 9%, the exact inverse of dorian. **So a trap is a property of the
implementation, not of the mechanism**: two tuned CBLAS libraries at the same shapes, and
the remedy for one is the mistake on the other. Note what the two items are between them: the FIX
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

### Telling a wake-up from a cold cache

A gap between calls slows the next one for two unrelated reasons -- a pool that parked its
workers, and a cache that lost the operand -- and the fix for one does nothing for the
other, so a probe that sees a gap cost has not yet learned anything. **Cap the pool to one
thread and re-take the SAME gapped measurement: what the cap removes was the wake-up, what
survives it was the cache.** It is cheaper than reading a thread count and it works on a
library that will not tell you one -- todo-651 had to use it, because Accelerate exports
none of the seven thread-query symbols. It found both halves at once: OpenBLAS's 17.4 ->
90.0 us at 288x288 collapses under the cap (a wake-up), while a small shape's 0.85 -> 1.75
us does not move under it at all (a cache). Nothing here is about BLAS; it applies to
anything with a pool behind it.

## Rule 2: price the CEILING of a proposal before building it

**Measure the most the idea could possibly be worth. If the ceiling is not significant,
the implementation is not needed, and the measurement is the deliverable.** This is
CLAUDE.md's "a change that measurement says is not worth its blast radius is a result;
land the measurement, not the change" turned into a procedure: the point is to find the
bound BEFORE writing the thing, because once it is written the pull to land it is real and
the comparison is no longer free.

The ceiling is usually reachable by a cheat that would be unacceptable to ship. Three
items closed this way without an implementation:

- `.todo/635` (`.kb/gpu.md`, "The last-axis fold's tiling"): the tiled last-axis fold wins
  1.33-1.96x as a KERNEL, which sounds decisive -- and then the census of the workload
  found that of 864 fold calls at the book's shapes, `inner == 1` happens ZERO times,
  every one being an axis-0 fold whose lanes are already coalesced. The ceiling was a
  speedup on no call sites.
- `.todo/650` (`.kb/gpu.md`): rather than widen the fused softmax's accept rule and then
  measure, it FORCED every one of the 144 heads to be accepted (`WIDEN=1`, materializing
  the mask to the score's shape) and measured that. 0.06 s over 13 steps, 0.8%, inside the
  noise. The accept rule was left alone.
- `.todo/655` (`.kb/gpu.md`, "Ceiling 2"): the chapter-2 step's copy profile attributed 2
  of its 4 remaining downloads a step to one mixed-width `linalg:add`, which reads as money
  on the table. The ceiling -- the operand rewritten to the matching width so the member is
  ACCEPTED -- removed those two downloads and made the step 9-19% SLOWER, with launches and
  device kernel time flat. **A copy count is not a cost until someone removes the copies and
  times it**, and a ceiling can come out NEGATIVE: the profile that suggests a change is
  exactly the evidence that would have got it built.

And one that shows the cost of skipping it: `.todo/646` (`.kb/gpu.md`, "Layer-norm's
affine on Metal") wrote the kernels first. They were correct and bit-identical on the first
run and a quarter faster per call, and the step did not move, so they were not kept -- the
per-call table that decided it against the change is exactly the ceiling that could have
been taken first, against the chain the decline already ran.

**A proposal can have more than one ceiling, one per population that pays, and the
populations do not pay for the same thing.** Ask who bears the cost BEFORE pricing it, and
then, for each of them, what KIND of cost it is -- listing the populations is not enough,
because it leaves room to assume one probe answers for both. `.todo/669` proposed dropping
the `f64` half of the fused-row PTX every compiled `--gpu` class carries. Two populations,
two different costs (`.kb/gpu.md`, "Pricing the f64 half of the fused-row family"): the
CARRIERS -- a Mac, a machine with no device, anyone handed the class for hardware that
cannot run it -- pay only to LOAD the class, and the RUNNERS pay to COMPILE the kernels.
Neither probe reaches the other's number. The JIT was measured by calling
`cuModuleLoadData` through FFM, which says nothing about loading a class; the class load
was measured over `java Prog`, and had to be BUILT not to include the JIT (the program
reaches `linalg:matmul` from behind a condition only false at run time, so the text is
embedded and the device never probed). Ceiling 1 is flat -- 783 KB out of the string moves
class load 61.9 to 59.9 ms, which is nothing -- and ceiling 2 is real but once per machine,
the driver's on-disk cache serving every later process in 4-7 ms whatever the size. Price
only the runners and the item reads as a second saved; price both and it is a no.

Note what a ceiling does NOT excuse: it is still a measurement, so rule 1 applies to it.
todo-650's ceiling was taken in the model with a structural count, not in a probe.

**And a ceiling is only a ceiling over the layers beneath it as they stand.** The same
proposal -- widen the fused softmax's accept rule -- was worth something before todo-650
removed the materialize under it and worth NOTHING after, on both backends independently:
0.8% on CUDA, and on Metal actually negative (0.709 against 0.684 s), because with the
round trips gone the only thing left to widening is the cost of building the two broadened
masks. A ceiling priced before a fix underneath lands is a ceiling over a workload that no
longer exists. This is rule 3 one level up: there, the baseline moved under an A/B; here,
the bound moved under a decision not to build.

## Rule 4: the premise came from a file, and the file can be over-corrected

A measurement can be clean and still be worthless because the QUESTION rested on a
sentence in `.kb` that is no longer true. CLAUDE.md already says a premise here is a
measurement rather than a law; this is the failure mode of the correction itself.

`.kb/gpu.md` had a list of what the Metal backend deliberately does not have. todo-495
brought that list up to date -- several of its entries had been built by then -- and while
doing so moved the index tier and the clip-norm pair to the built side as well, which is
wrong: `MetalGemm.take`/`scatter`/`sumSquares` and their float twins return `false` and
`null` unconditionally, with no kernel behind them. Two later sweeps read the corrected
list as current. The second one asked whether clip-norm was reaching the device in the
tests, was told by the other backend's session that the programs offer it 634 times a step
and it is accepted 634 times -- **a correct measurement, on CUDA, of a question that does
not exist on Metal**, where there is no member to accept. Nobody measured anything wrong.

**So when you correct a premise here, say what you took OUT and what you left, not just
what is true now.** An over-correction reads exactly like a correction; the only thing
that distinguishes them is the record of what moved. And when a measurement's question
comes from a `.kb` sentence rather than from the code, check the code for the clause the
sentence is about before spending the machine time -- one `grep` for the member's own
`return false` would have ended this chain at step 2.

**Run forwards it costs one file read, and it can UNLOCK a claim rather than only prevent a
wrong one.** todo-665 was raised on a premise about the kernel rather than about a `.kb`
sentence -- that the Metal fused row members reduce a row in a THREADGROUP TREE, which a
sequential replay could not be expected to match -- and the item was written expecting a
divergence and a bound in place of bit-identity. Reading `gemm.metal` before building the
measurement showed the fold is one thread per row, sequential in software binary64, so the
stronger pin was available and went in unweakened (`.kb/gpu.md`, "The libm-free members
against a SEQUENTIAL replay"). The rule is not "distrust `.kb`": it is that a premise ABOUT
CODE is checkable in the code, whoever wrote it down and however recently.

### The premise can come from another implementation, and that one can fail in ONE place

The same failure with an external oracle instead of a `.kb` sentence: a question settled by
"SBCL does X" inherits whatever SBCL is wrong about, and you find that out later. It
happened on 2026-09-03. `.todo/648` settled ten signed-zero rows partly against SBCL, and
`.todo/660` then established that SBCL is NOT an oracle for the two-argument float rounding
operators -- it rounds `a/b` to `f64` and converts that exactly, so its answer is the
quotient's rounding rather than the exact one.

**That did not cost the ten rows anything, and the reason it did not is the whole point.**
`.kb/linalg-simd.md` recorded, per row, WHAT the decision rested on rather than only that it
matched: `(+ -0.0 -0.0)` on IEEE 754, `signum`/`sin`/`tan` on oddness, `eql` versus `=` on
an explicit CLHS sentence, the `min`/`max` ties on SBCL *because CLHS leaves them
implementation-defined* -- which is a choice among conforming answers, so a peculiarity
there could not make the result non-conforming. Not one of those rows reaches a float
division, which is exactly what todo-660 disqualified. The check took a reading of one file;
without the per-row basis it would have taken re-deriving ten decisions.

**So when a decision leans on another implementation, write what you trusted it FOR.**
"Matches SBCL" is not a basis, it is an observation; "SBCL, because CLHS makes this
implementation-defined and this is the conforming choice it makes" survives the day someone
finds a thing SBCL gets wrong. Better still where it is available: `.todo/652` reopened one
of those same rows and settled it on the implementation contradicting ITSELF -- `mod` and
`rem` disagreeing with the second value of its own `truncate` -- and a conclusion that never
used the oracle cannot be undone by the oracle.

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
