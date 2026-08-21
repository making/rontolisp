# The optimizer update and the RNG are boxed Lisp loops on no acceleration seam

Difficulty: Medium

Surfaced by the `--gpu` profiling (2026-08-21), which is why the numbers below are in
`.kb/gpu.md` rather than here -- read its "The strided tier, and why residency was NOT
built" section first, and `.kb/linalg-simd.md` for the interception protocol this item
wants to reach two more members with. `.kb/torch.md` is the package being accelerated.

## The case

Once `--gpu` had taken the matrix product, the transcendentals and the strided shapes, a
JFR execution profile of `examples/llm-from-scratch/train-gpt-soseki.lisp` under
`--gpu --simd` on the JVM class output said the remaining cost is **not `linalg:` any
more**. Top frames over a 40-step run, 1159 samples:

| frame | samples | what it is |
|---|---|---|
| `TORCH::%O-ADAM-STEP` | 356 (31%) | the AdamW update, a per-element BOXED Lisp loop |
| `_dbl` | 167 (14%) | boxing a double -- mostly that loop's and the RNG's |
| `_fvAset1` | 91 (8%) | `(setf (row-major-aref ...))`, same loop |
| `LINALG:RAND` / `RANDN` / `%LA-RNG-NEXT` | 159 (14%) | the dropout masks, boxed RNG loops |
| every device copy in the step | 17 (1.5%) | what device residency would remove |

**Between them the two loops are about half of what a `--gpu --simd` training step costs**
(0.21 s at the notebook's shapes on a GB10), and neither is reachable by `--simd`,
`--blas` or `--gpu`. That is the whole case: the acceleration seam this project spent
todo-123 on cannot move this program further, because the program no longer spends its
time on the seam.

## The two members

**`torch::%o-adam-step`** (`torch.lisp`) is PyTorch's Adam/AdamW rule as a `do` loop over
`row-major-aref` / `(setf (row-major-aref ...))`, per element per parameter, over four
packed arrays at once: the parameter, its gradient, and the two moment buffers. Each
element boxes at least one double. It is a textbook fused element-wise update -- five
multiplies, a `sqrt` and a divide over four aligned arrays with no cross-element
dependency -- so it is exactly the shape both a lane loop and a device map want.

**`linalg:rand` / `linalg:randn`** (`linalg.lisp`) are `do` loops calling
`linalg::%la-rng-next`, which is Wichmann-Hill over three special variables. `randn` sums
twelve draws per element (Irwin-Hall). The state is sequential, which LOOKS like it
forbids a parallel kernel -- and does not, see below.

## What to build, and the one constraint that decides it

The obvious route is the existing seam: a Java kernel per member with the null-sentinel
decline, the scalar defun left as the oracle (`.kb/linalg-simd.md`). Two decisions are
not obvious and should be made before any code:

1. **`%o-adam-step` is a `torch:` name, and the seam intercepts `linalg:` names.** Either
   the seam widens to take a `torch::` member -- one new touch point in each of the three
   backends -- or the rule is rewritten over `linalg:` members that ARE intercepted, at
   the cost of a dozen whole-array temporaries per parameter per step, which on this
   program's shapes may well cost more than it saves. Measure the rewrite before
   assuming the widening is necessary; it is the cheaper experiment by far.
2. **The RNG must produce the SAME sequence, and can.** `linalg:seed` promises that one
   seed reproduces one sequence on every backend, and the examples' expected output is
   pinned to it, so a counter-based generator is not an option -- but each state is
   `s <- (a * s) mod m`, whose k-th term is `a^k * s mod m` in closed form. A kernel can
   therefore fill element k directly, in any order, and write back the final state
   (`a^n * s mod m`) exactly where n scalar draws would have left it. The float half --
   three divides, a sum, and the `frac` by compares -- must be reproduced operation for
   operation, in that order, or the result is not bit-identical and the contract breaks.

## Acceptance

- `train-gpt-soseki.lisp` and `examples/ml/tiny-llm.lisp` print byte-identical output with
  and without every acceleration flag, as they do today: this is a speed item and NOT a
  precision one, at either width, on any backend. Both members compute in the array's own
  width from the same seeds, so there is no FMA and no device libm to excuse a difference.
- A JFR profile of the same 40 steps shows both frames off the top of the list, and the
  step time moves by something worth the complexity -- if `%o-adam-step` falls to a tenth
  the whole step should drop by about a quarter, which is the number to check against.
- The four-backend rule holds: the interpreter, the JVM class output and both WASM
  backends still agree, and a machine with no `jdk.incubator.vector` runs the defun.

## References

- `.kb/gpu.md` -- the profile above, in full, plus how to take another one (the compiled
  Lisp functions carry no line-number table, so a profile filtered on `line:` sees only
  the Java half and reports the wrong answer by 6x).
- `.kb/linalg-simd.md` -- the declined-input protocol, the decline sentinel, the precision
  contract and the three per-backend touch points a new member needs.
- `.kb/torch.md`, `.kb/linalg.md`, `.kb/vec.md`.
