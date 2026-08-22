# The host reads a lazy training step still makes: clip-grad-norm's sum, the embedding, the small folds

Difficulty: Medium

Filed 2026-08-23 when `.todo/491` closed; `.kb/gpu.md` "A result comes home on first host
touch" lists them under "what the profile says is left". With results staying on the
device, a 40-step `train-gpt-soseki` run at the notebook's shapes (JVM class output,
`--gpu --simd`) still downloads ~450 MB, counted by the caller of `_gpuMaterialize`:

| caller | downloads / 40 steps | MB | why it reads on the host |
|---|---|---|---|
| `torch:clip-grad-norm` -> `linalg::%la-sum-squares` | 760 | 278 | a sequential double fold whose contract is the defun's order, which a parallel reduction cannot keep; `%la-scale` after it already runs in place on the device |
| `%t-unbroadcast`'s small axis folds | 240 | 45 | a fold with fewer than 256 output cells is declined as a single-threaded device loop (`FOLD_MIN_CELLS`), so its big operand comes home |
| `torch:index-select` / `take-rows`, `linalg:gather`, `scatter-rows` (the embedding, the cross-entropy pick) | 120 | 44 | index-driven copies with no device member; 4.7 MB a step at the book's shapes, where the table is 3038 x 384 |
| `torch:mean` / `torch:var` / the loss | 240 | 6 | whole-array reductions (not members) and scalars |

None of them is large at these shapes (7 MB a step for the sum, ~12 MB in all against
the 220 MB that 491 removed), which is why they were left. Three of the four have a clear
shape:
- `%la-sum-squares` on the device needs a reduction whose order the CPU kernel ALSO uses
  -- a fixed blocked order both sides can follow -- or an explicit break with the defun's
  order for this one member (the precision contract already breaks for products and
  transcendentals; a clip norm is used only as a scale);
- the few-cell fold needs a second kernel: one block per output cell with a sequential
  walk split across the block in the fold's own order is impossible, so either a blocked
  order both sides use (as above) or accept the download;
- `take-rows` / `gather` / `scatter-rows` are a gather kernel with an index array (the
  `copy_fXX` kernel plus one indirection), `scatter-rows` in place with atomics (its CPU
  twin adds in index order; repeated indices change the fold order).

## Acceptance

The 40-step run's download count by caller re-measured, each row above either moved to
the device with its precision contract stated in `.kb/gpu.md`, or left with a number
saying why.
