# Measurement: is the number answering the question you asked?

Backend-neutral measurement rules. The mechanisms and the numbers stay in the file that
owns each one (`.kb/gpu.md`, `.kb/simd-parallel.md`, `.kb/linalg-blas.md`,
`.kb/linalg-simd.md`); this file names the general form.

## Rule 1: a probe's shape is not the step's shape
An ISOLATED PROBE answers "what does this member cost", NOT "what does it cost in the
workload": it decides for itself whether the operand is resident, whether the pool's threads
are awake, whether the clocks are up, and whether anyone waits for the result.

- **A probe proposes, the workload disposes.** An isolated number is a HYPOTHESIS; land it
  only after the workload -- or a structural count taken inside it -- agrees.
- **Prefer a structural count to a per-call wall** (nsys `cuMemcpyHtoD` /
  `cuCtxSynchronize` / device kernel time, a download or command-buffer counter, a launch
  census). It is the wall that lies.
- **Record beside each number which traps it was checked against, and the CONDITIONS**:
  thread count, gap between calls, whether the result was forced home. Placement without
  conditions and conditions without placement each lose the number.
- **A measurement that says the change is not worth its blast radius is a result**
  (CLAUDE.md). A probe that flatters a change and one that flatters the status quo are wrong
  the same way.

### The four traps
1. **The pool's workers never go idle in a back-to-back loop**, so no dispatch pays the
   unpark chain the real caller pays between calls (`.kb/simd-parallel.md`,
   `.kb/linalg-blas.md`: 288x288 `#f` gemv 17.4 us hot, 90.0 us with ~200 us between calls,
   13.3 us capped to one thread). **The trap is a property of the implementation, not the
   mechanism** -- Accelerate pays no wake-up and the sign inverts there.
2. **What the probe times may be the ENQUEUE, not the work** under lazy results with
   asynchronous submission (`.kb/gpu.md`). Discipline where there is no profiler: print BOTH
   columns, one forcing every result home with a single element read and one not. Worst for a
   change that REMOVES A SYNCHRONISATION POINT.
3. **What the probe holds may be RESIDENT when the workload's is not** -- a probe's operands
   are built once, a workload's intermediates are new every iteration.
4. **What the probe leaves behind may be the wrong CLOCKS**: a device that idles through a
   host-side gap drops them. Metal only; CUDA measured flat to within 1% out to a 32 ms gap.

**Not every threaded number is trap 1, and ratios do not compare across items** -- check the
denominator before citing a ratio as evidence. **"We did not hit any of these" must be
CHECKED**: absence measured (identical copy and synchronisation counts), not argued.

**Telling a wake-up from a cold cache**: cap the pool to one thread and re-take the SAME
gapped measurement -- what the cap removes was the wake-up, what survives was the cache.
Cheaper than reading a thread count and works on a library that will not report one.

## Rule 2: price the CEILING of a proposal before building it
**Measure the most the idea could possibly be worth. If the ceiling is not significant, the
implementation is not needed and the measurement is the deliverable.** Find the bound BEFORE
writing the thing -- the ceiling is usually reachable by a cheat that would be unacceptable
to ship (force every head accepted; rewrite an operand to the matching width).

- **A copy count is not a cost until someone removes the copies and times it**, and a ceiling
  can come out NEGATIVE (removing 2 of 4 downloads a step made it 9-19% SLOWER).
- **A proposal can have more than one ceiling, one per population that pays.** Ask who bears
  the cost and what KIND it is before pricing: CARRIERS pay to LOAD a class, RUNNERS pay to
  COMPILE the kernels, and neither probe reaches the other's number. Price only the runners
  and it reads as a win; price both and it is a no.
- A ceiling is still a measurement, so rule 1 applies to it, and **a ceiling is only a
  ceiling over the layers beneath it as they stand**.

## Rule 3: an A/B whose baseline moved is not an A/B
**When several sessions push to `develop`, the arm you are NOT changing can be changed under
you** -- not rule 1, and not the semantic conflict CLAUDE.md warns about. A pair measured
9.2% was wrong by 5x because a commit landing mid-run moved the untouched DECLINE arm.

**What to do**: take both arms from ONE tree, re-take the baseline after any merge that lands
between the two halves of a comparison, and say which commit each arm was built at.

## Rule 4: the premise came from a file, and the file can be over-corrected
A measurement can be clean and worthless because the QUESTION rested on a `.kb` sentence that
is no longer true. This is the failure mode of the CORRECTION.

- **When you correct a premise here, say what you took OUT and what you left.** An
  over-correction reads exactly like a correction.
- **A premise ABOUT CODE is checkable in the code** -- one `grep` for a member's own
  `return false` ends the chain. It also UNLOCKS claims (reading `gemm.metal` made a
  bit-identity pin available where a threadgroup-tree premise had predicted divergence).
- **A `.todo` item's premise is a measurement too, dated the day it was filed. The first move
  on an item filed more than a few days ago is to re-take its own numbers, and finding them
  gone is the deliverable.**
- **A premise can come from another implementation, and that one can fail in ONE place.**
  SBCL is NOT an oracle for the two-argument float rounding operators (it rounds `a/b` to
  `f64` and converts that). **When a decision leans on another implementation, write what you
  trusted it FOR** -- "matches SBCL" is an observation, not a basis. Better: settle it on the
  implementation contradicting ITSELF (`mod`/`rem` against its own `truncate`).

## Rule 5: when a number is wrong, the instrument is the first suspect
Six failures that look identical from outside (an unexpected ratio) and are not.

- **A. The instrument is broken.** A per-row dispatch through a five-implementation interface
  went megamorphic and timed BOXING. *Caught by an ordering arithmetic forbids* (FMA slower
  than the multiply-and-add it replaces). **A ranking that violates something you know a
  priori indicts the instrument** -- put such a variant in the harness deliberately and check
  it lands where it must before reading any other row.
- **B. Buried in noise.** Different answers to the same question; quiet the machine, take
  medians, discard the numbers.
- **C. Measuring two different states, and both numbers are real.** Repetition never
  converges -- one harness returned three ratios for one shape inside ONE process, the spread
  crossing 1.0. Mechanism: one generic timing method shared by both kernels, baseline always
  first, so an ordering decides whose profile it is.
- **D. Instrument and subject both right, but the subject is only a PART of the product.** A
  real 0.88-0.97x loss at 48 columns coexisted with a 1.09x model win, because the other
  shapes dominate the token. **A probe measures the member; a product runs a MIX, and the mix
  has its own sign.**
- **E. The box is loaded, so the RATIO is inflated -- not just noisy.** Alternating in pairs
  preserves the DIRECTION, not the SIZE (median 1.088 at load 288 vs 1.062 at load 0.6).
  **Publish the number from a quiet window and record absolutes as well as the ratio.**
- **F. The instrument was never validly started -- and the SIZE of the damage cannot tell you
  so.** 37 failures / 1496 errors looked like a missing `clean` and was a real regression in
  the shared compile path; the two have the SAME shape. **Re-run from `clean` before
  reporting a suite result.** The SHAPE survives where the count does not (17 errors, 100%
  `runStandalone` and 0 outside it): **a suite can hold a defect invisibly for as long as
  every case sits on one side of its condition, and the thorough-looking half is the half
  that hides it.** The counting rule checks the PROCEDURE: fewer than ~220
  `target/surefire-reports/*.txt` means the run was cut off, whatever its failure count says.

### What to do about C, D and E
1. **Make the harness call the kernel the way the product calls it** -- one timing method per
   kernel, called by name, no interface/lambda/method reference, so each is monomorphic and
   separately compiled.
2. **One shape per process, and one KERNEL per process**: take the ratio across two JVMs that
   never saw the other arm.
3. **Then go to the workload** -- it has only one JIT state. Same binary before and after,
   same token count, quiet box, plus the `md5sum` of the output, which must not move if the
   change was meant to be invisible. Build both arms in separate worktrees and ALTERNATE the
   runs in pairs; consecutive blocks bias in opposite directions as load drifts.
4. **Record both numbers and say they disagree.**

## Rule 5: a parity test with a hand-written oracle is two asserts, not one
**A test claiming PARITY asserts the two backends against EACH OTHER. A hand-written expected
value is a DIFFERENT claim and goes in its own assert, on its own line, under its own name.**
Folded into one assertion, a failure no longer says which of the three parties -- backend A,
backend B, the string you typed -- is wrong; a wrong literal was reported as "the interpreter
does not narrow on this path".

**What to do**: keep `both()`-style helpers to the parity claim and name the oracle claim
separately (`...AgreeWithEachOther` and `...AnswerTheRoundedSum`). Before attributing a
failure to a backend, count the parties -- a hand-written value is a party.
