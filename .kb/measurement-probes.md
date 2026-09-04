# Measurement: is the number answering the question you asked?

Backend-neutral measurement rules. The mechanisms and the numbers stay in the file that
owns each one (`.kb/gpu.md`, `.kb/simd-parallel.md`, `.kb/linalg-blas.md`,
`.kb/linalg-simd.md`); this file names the general form and points at them.

## Rule 1: a probe's shape is not the step's shape

Most acceleration numbers here come from an ISOLATED PROBE: a small program calling one
member N times at the workload's shapes and dividing (an end-to-end wall swings several
percent, so a member worth a few hundred microseconds is invisible in it). That answers
"what does this member cost", NOT "what does it cost in the workload". A probe decides for
itself whether the operand is resident, whether the pool's threads are awake, whether the
clocks are up, and whether anyone waits for the result.

- **A probe proposes, the workload disposes.** An isolated number is a HYPOTHESIS. Land it
  only after the workload -- or a structural count taken inside the workload -- agrees.
- **Prefer a structural count to a per-call wall.** It counts events instead of timing the
  host: nsys `cuMemcpyHtoD` / `cuCtxSynchronize` / device kernel time, a download or
  command-buffer counter, a launch census. It is the wall that lies -- and the count is
  what survives a wall that goes wrong inexplicably (a case where the wall missed its
  projection in both directions while `cuMemcpyDtoH` -34 / `cuMemcpyHtoD` -44 matched the
  prediction exactly).
- **Record beside each number which traps it was checked against, and the CONDITIONS:
  thread count, gap between calls, whether the result was forced home.** Placement without
  conditions and conditions without placement each lose the number. A missing thread count
  once cost a day of re-measurement, and the answer could not be inferred (Accelerate
  exports no thread query; it had to be found by seeing where `VECLIB_MAXIMUM_THREADS=1`
  moves the library's time).
- **A measurement that says the change is not worth its blast radius is a result**
  (CLAUDE.md). A probe that flatters a change and a probe that flatters the status quo are
  wrong the same way.

### The traps

**1. The pool's workers never go idle in a back-to-back loop.** A tight loop keeps every
worker hot, so no dispatch pays the unpark chain the real caller pays between calls.
`.kb/simd-parallel.md` ("Back-to-back calls are not the workload"): 768x288 measured 1.8x
back to back and 0.5-0.9x with a 20-200 us gap, and the in-situ result matched end-to-end
tok/s. Same shape through OpenBLAS's pool (`.kb/linalg-blas.md`): 288x288 `#f` gemv is
17.4 us hot, 90.0 us with ~200 us of unrelated work between calls, 13.3 us capped to one
thread. **The trap is a property of the implementation, not the mechanism**: Accelerate
runs every decode-loop shape on ONE thread and pays no wake-up where it does thread
(`dgemv 2048x2048` 127-132 us hot vs 124-128 us gapped), so the flag is a 1.90x win there
and capping costs 9% -- the inverse of the x86 case. The FIX for the trap (workers spinning
on an epoch, parking after 1 ms idle -- "what every BLAS does") is the same property that
makes a back-to-back probe of a tuned pool lie.

**2. What the probe times may be the ENQUEUE, not the work.** Under lazy results with
asynchronous submission an accepted member returns before the device runs it, so its wall
is the submission. `.kb/gpu.md`, "Asynchronous command buffers on Metal": the same probe,
no implementation change, read 8.02 / 1.22 ms before that work and 8.32 / 0.017 ms after.
Discipline where there is no profiler: print BOTH columns -- one forcing every result home
with a single element read, one not -- since a member that ran on the host costs the same
in both (`.todo/123-gpu-acceleration/mtl-where-mask-width.lisp`,
`mtl-layer-norm-affine.lisp` are shaped that way). Worst for a change that REMOVES A
SYNCHRONISATION POINT: on the enqueue column a round trip that is gone and one that merely
moved behind the asynchrony look identical.

**3. What the probe holds may be RESIDENT when the workload's is not.** A probe's operands
are built once and reached every repetition, so a cache that adopts on second sight has
them from the third repetition; a workload's intermediates are new every iteration and
never get there. `.kb/gpu.md`, "What the fold's SHAPE decline costs on this backend": a
probe said the host round trip was gone while the real model still paid 192 a step.

**4. What the probe leaves behind may be the wrong CLOCKS.** A host-side member leaves a
gap; a device that idles through it drops its clocks, so the next accepted call is not the
call a back-to-back probe timed. `.kb/gpu.md`, "The map threshold at the straddling shape"
-- Metal only; CUDA measured flat to within 1% out to a 32 ms gap.

**Not every threaded number is trap 1, and ratios do not compare across items**: a 0.5-0.9x
is parallel GEMV against SERIAL (the cost of waking the pool); a 6-9x is Accelerate against
the `FloatVector` LANE KERNEL. Check the denominator before citing a ratio as evidence.

Traps 1 and 4 are the same sentence about two different resources, which argues a fifth
form exists. When you find it, add it here and leave its numbers where they were measured.

**"We did not hit any of these" must be CHECKED.** The right shape: compile a variant with
the host read taken out, profile both, show the copy and synchronisation counts identical
(292/302/204 against 4/14/12, unchanged) -- absence measured, not argued.

### Telling a wake-up from a cold cache

A gap slows the next call for two unrelated reasons (a parked pool; a lost cache line) and
the fixes are different. **Cap the pool to one thread and re-take the SAME gapped
measurement: what the cap removes was the wake-up, what survives was the cache.** Cheaper
than reading a thread count and works on a library that will not report one. OpenBLAS's
17.4 -> 90.0 us at 288x288 collapses under the cap (wake-up); a small shape's 0.85 -> 1.75
us does not move (cache). Applies to anything with a pool behind it.

## Rule 2: price the CEILING of a proposal before building it

**Measure the most the idea could possibly be worth. If the ceiling is not significant,
the implementation is not needed and the measurement is the deliverable.** Find the bound
BEFORE writing the thing -- once it is written the pull to land it is real. The ceiling is
usually reachable by a cheat that would be unacceptable to ship. Examples (all
`.kb/gpu.md`):

- Tiled last-axis fold: 1.33-1.96x as a KERNEL, then the workload census found `inner == 1`
  happens ZERO times in 864 fold calls -- a speedup on no call sites.
- Fused softmax accept rule: rather than widen it and measure, FORCE all 144 heads accepted
  (`WIDEN=1`, materializing the mask) -- 0.06 s over 13 steps, 0.8%, inside the noise.
- "Ceiling 2": 2 of 4 remaining downloads a step attributed to one mixed-width
  `linalg:add`; rewriting the operand to the matching width removed them and made the step
  9-19% SLOWER, launches and device kernel time flat. **A copy count is not a cost until
  someone removes the copies and times it**, and a ceiling can come out NEGATIVE.
- Counter-example ("Layer-norm's affine on Metal"): kernels written first were correct,
  bit-identical, a quarter faster per call, and the step did not move -- the per-call table
  that declined them was exactly the ceiling that could have been taken first.

**A proposal can have more than one ceiling, one per population that pays.** Ask who bears
the cost before pricing, and what KIND of cost it is. Dropping the `f64` half of the
fused-row PTX had two: CARRIERS (a Mac, a machine with no device) pay only to LOAD the
class; RUNNERS pay to COMPILE the kernels. Neither probe reaches the other's number --
`cuModuleLoadData` through FFM says nothing about class loading, and the class-load probe
had to be BUILT not to include the JIT. Ceiling 1 is flat (783 KB out of the string moves
class load 61.9 -> 59.9 ms); ceiling 2 is real but once per machine (the driver's on-disk
cache serves later processes in 4-7 ms whatever the size). Price only the runners and it
reads as a second saved; price both and it is a no.

A ceiling is still a measurement, so rule 1 applies to it -- take it in the model with a
structural count, not in a probe. And **a ceiling is only a ceiling over the layers beneath
it as they stand**: widening the fused softmax accept rule was worth something before the
materialize under it was removed and nothing after, on both backends (0.8% on CUDA,
negative on Metal, 0.709 vs 0.684 s).

## Rule 3: an A/B whose baseline moved is not an A/B

**When several sessions push to `develop`, the arm you are NOT changing can be changed
under you.** Not rule 1 (the probe's shape can be perfect) and not the semantic conflict
CLAUDE.md warns about (which is two sides touching one mechanism): here only one side is
touched, by a third party, and `git merge` has nothing to say about it.

The fused layer-norm pair measured 9.2% over the decline and was wrong by 5x: a commit
landing mid-run pushed the DECLINE arm from 1.798 to 1.680 s (the decline is the arm that
ran the host chain the fix stopped materializing for). The fused arm was untouched, so the
gap opened on its own; re-measured on one tree the pair is a coin flip (1.680 vs 1.702 s).

**What to do**: take both arms from ONE tree, and re-take the baseline after any merge that
lands between the two halves of a comparison. If a run spans a merge, say which commit each
arm was built at.

## Rule 4: the premise came from a file, and the file can be over-corrected

A measurement can be clean and worthless because the QUESTION rested on a `.kb` sentence
that is no longer true. CLAUDE.md already says a premise here is a measurement, not a law;
this is the failure mode of the CORRECTION.

A `.kb/gpu.md` list of what the Metal backend deliberately lacks was brought up to date and
in the process moved the index tier and the clip-norm pair to the built side -- wrong:
`MetalGemm.take`/`scatter`/`sumSquares` and their float twins return `false`/`null`
unconditionally, with no kernel behind them. Two later sweeps read the corrected list as
current; one produced a correct CUDA measurement of a question that does not exist on
Metal.

- **When you correct a premise here, say what you took OUT and what you left.** An
  over-correction reads exactly like a correction; only the record of what moved
  distinguishes them.
- **A premise ABOUT CODE is checkable in the code** -- one `grep` for the member's own
  `return false` would have ended that chain at step 2. It also UNLOCKS claims: reading
  `gemm.metal` before building a measurement showed the Metal fused-row fold is one thread
  per row, sequential in software binary64, so a bit-identity pin was available where a
  threadgroup-tree premise had predicted a divergence (`.kb/gpu.md`, "The libm-free members
  against a SEQUENTIAL replay").
- **A `.todo` item's premise is a measurement too, dated the day it was filed.** Four items
  in one week were closed by finding the premise gone rather than by doing what they asked.
  **The first move on an item filed more than a few days ago is to re-take its own numbers,
  and finding them gone is the deliverable.**

### The premise can come from another implementation, and that one can fail in ONE place

A question settled by "SBCL does X" inherits whatever SBCL is wrong about. SBCL is NOT an
oracle for the two-argument float rounding operators: it rounds `a/b` to `f64` and converts
that exactly, so its answer is the quotient's rounding rather than the exact one.

That cost ten signed-zero rows nothing, because `.kb/linalg-simd.md` recorded per row WHAT
each decision rested on: `(+ -0.0 -0.0)` on IEEE 754; `signum`/`sin`/`tan` on oddness;
`eql` versus `=` on an explicit CLHS sentence; the `min`/`max` ties on SBCL *because CLHS
leaves them implementation-defined* -- a choice among conforming answers. None reaches a
float division.

**So when a decision leans on another implementation, write what you trusted it FOR.**
"Matches SBCL" is an observation, not a basis. Better where available: settle it on the
implementation contradicting ITSELF (`mod`/`rem` disagreeing with the second value of its
own `truncate`) -- a conclusion that never used the oracle cannot be undone by it.

## Rule 5: when a number is wrong, the instrument is the first suspect -- and there are six ways it can be wrong

Six failures that look identical from outside (an unexpected ratio) and are not.

**A. The instrument is broken.** A probe dispatching the row kernel through a
five-implementation interface ONCE PER ROW went megamorphic, the JIT stopped inlining the
Vector API, and cost scaled with the number of live vectors -- it was timing BOXING, not
the fold, and reported a 0.48x regression that does not exist. *Caught by an ordering
arithmetic forbids*: four-accumulator-with-FMA at 0.51x below plain four-accumulator at
0.48x, when FMA cannot be slower than the multiply-and-add it replaces. **A ranking that
violates something you know a priori indicts the instrument.** Put such a variant in the
harness deliberately (a copy of the baseline, a strictly-fewer-instructions arm) and check
it lands where it must before reading any other row.

**B. The instrument is buried in noise.** Real effect, smaller than what else moves on the
box. Announces itself by different answers to the same question; fixed by quieting the
machine and taking medians; numbers discarded.

**C. The instrument is measuring two different states, and both numbers are real.**
Nothing looks broken and repetition never converges. One harness returned THREE ratios for
the same shape (256x48) inside ONE process -- 0.92x, 1.29x, 1.21x on aarch64; 0.93x and
1.24x on x86-64, where the spread crosses 1.0 and changes the SIGN. Reproduces on a quiet
box and a loaded one, which rules out B. Mechanism: a single generic timing method shared
by both kernels (`time(Gemv, ...)`) with the baseline always first -- two implementations,
one compilation, one profile, and an ordering deciding whose profile it is.

**D. The instrument is right, the subject is right, and the subject is only a PART of the
product.** A solo probe measured a real four-accumulator loss at 48 columns on x86-64
(0.88-0.97x under Graal, 0.74-0.93x under C2, ten fresh JVMs each, neither spread
containing 1.0), and the model still got 1.09x faster: stories15M's two attention GEMVs are
48 columns and the rest are 288+, and the second group dominates the token. **A probe
measures the member; a product runs a MIX, and the mix has its own sign.**

**E. The instrument is fine and the box is loaded, so the RATIO is inflated -- not just
noisy.** Alternating in pairs preserves the DIRECTION, not the SIZE: the same twelve pairs
measured 1.08-1.24x (median 1.088) at load 288 and 1.003-1.073x (median 1.062) at load 0.6.
Contention costs the slower build more. **Take the number you publish in a quiet window,
and record absolute values as well as the ratio.**

**F. The instrument was never validly started -- and the SIZE of the damage cannot tell
you so.** A full suite reported 9927 tests / 37 failures / 1496 errors, and the run had
been started without `clean` after a checkout -- the obvious explanation, and the WRONG
one: re-run from `clean` the same commit produced the same numbers, because `-o Prog.class`
was refusing every program that did not call `widen-float-bits` (a first-class wrapper
emitted unconditionally against a helper emitted only on demand).

- **A failure count that large means the failures are not independent -- look for one
  shared cause instead of reading traces.** But it cannot name the layer: a stale
  `target/classes` and a regression in the shared compile path have the SAME shape.
- **Re-run from `clean` before reporting a suite result.** One command, decisive either
  way.
- *The SHAPE is a different instrument and it survives*: the same regression produced 17
  `CiSpecE2eTest` errors, 100% `runStandalone` and 0 outside it, because the corpus cases
  call `widen-float-bits` and the standalone ones do not. **A suite can hold a defect
  invisibly for as long as every case sits on one side of its condition, and the
  thorough-looking half is the half that hides it.**
- The counting rule checks the PROCEDURE, not the size: a run whose
  `target/surefire-reports/*.txt` count is short of ~220 was cut off, whatever its failure
  count says.

### What to do about C, D and E

1. **Make the harness call the kernel the way the product calls it** -- one timing method
   per kernel, called by name, no interface, no lambda, no method reference, so each is
   monomorphic and separately compiled and neither inherits the other's profile
   (`.todo/480-.../Solo.java` shape).
2. **One shape per process, and one KERNEL per process**: take the ratio across two JVMs
   that never saw the other arm.
3. **Then go to the workload.** A probe cannot settle which JIT state is real; the program
   can, because it has only one. Read throughput on the same binary before and after (same
   token count, quiet box, e.g. `LLAMA2_STEPS=256 ... | grep 'achieved tok/s'`) and the
   `md5sum` of the output, which must not move if the change was meant to be invisible.
   Build both arms in separate worktrees at the two commits and ALTERNATE the runs in pairs
   (`for i in $(seq 1 15); do run_before; run_after; done`) -- a box whose load drifts
   biases two consecutive blocks in opposite directions.
4. **Record both numbers and say they disagree.** A limitation naming the two states beats
   a single number with the disagreement quietly resolved.

## Rule 5: a parity test with a hand-written oracle is two asserts, not one

**A test claiming PARITY asserts the two backends against EACH OTHER. A hand-written
expected value is a DIFFERENT claim and goes in its own assert, on its own line, under its
own name.** Folded into one assertion, a failure no longer says which of the three parties
-- backend A, backend B, the string you typed -- is wrong.

`assertThat(both(program)).isEqualTo("(249750.0 499.0 0.5)")`, where `both` ran the program
on the interpreter and the JVM and asserted them equal: the parity assert PASSED and the
literal was wrong (499.5 is not representable at bfloat16 -- the ulp on [256, 512) is 2 --
and both backends correctly answered 500.0). Read off the AssertJ expected/actual, the
failure was reported as "the interpreter does not narrow on this path".

**What to do**: keep `both()`-style helpers to the parity claim and name the oracle claim
separately (`...AgreeWithEachOther` and `...AnswerTheRoundedSum`), so the red line names
the party. Before attributing a failure to a backend, re-read the assertion and count the
parties -- a hand-written value is a party.
