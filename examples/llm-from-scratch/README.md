# LLM from Scratch — the Transformer and GPT chapters, in rontolisp

A rontolisp port of chapters 2 and 3 of [**『作ってわかる大規模言語モデルの仕組み』**
(Elith Inc., Nikkei BP, 2026) — its sample
repository](https://github.com/elith-co-jp/book-llm-from-scratch): the reusable
`llm_from_scratch/transformer/` and `llm_from_scratch/gpt/` packages, the
chapter 2 notebooks (sections 2.2 - 2.5) and the chapter 3 ones (section 3.2 and
the 漱石 training notebook). Nothing from that repository is vendored here; this
is a rewrite of its PyTorch code, which maps onto the [`torch`
package](../../doc/en/guides/neural-networks.md) almost line for line, and onto
[`linalg`](../../doc/en/guides/linear-algebra.md) for the array math underneath
it.

Everything here runs on all four backends; nothing is downloaded and nothing is
vendored. The plots are the one thing that does not port — each notebook figure
becomes the numbers it was drawn from, which is also what makes the example
testable.

## What maps to what

| book | here |
| --- | --- |
| `transformer/attention.py` | [`transformer/attention.lisp`](transformer/attention.lisp) |
| `transformer/utils.py` | [`transformer/utils.lisp`](transformer/utils.lisp) |
| `transformer/transformer.py` | [`transformer/transformer.lisp`](transformer/transformer.lisp) |
| its `__main__` shape check | [`transformer/shapes.lisp`](transformer/shapes.lisp) |
| `notebooks/chapter02/section2.ipynb` | [`chapter02/section2.lisp`](chapter02/section2.lisp) |
| `notebooks/chapter02/section3.ipynb` | [`chapter02/section3.lisp`](chapter02/section3.lisp) |
| `notebooks/chapter02/section4.ipynb` | [`chapter02/section4.lisp`](chapter02/section4.lisp) |
| `notebooks/chapter02/section5.ipynb` | [`chapter02/section5.lisp`](chapter02/section5.lisp) |
| `gpt/tokenizer.py` | [`gpt/tokenizer.lisp`](gpt/tokenizer.lisp) |
| `gpt/dataset.py` | [`gpt/dataset.lisp`](gpt/dataset.lisp) |
| `gpt/model.py` | [`gpt/model.lisp`](gpt/model.lisp) |
| `gpt/trainer.py` | [`gpt/trainer.lisp`](gpt/trainer.lisp) |
| — (the same idea, for `gpt/`) | [`gpt/shapes.lisp`](gpt/shapes.lisp) |
| `notebooks/chapter03/section03_tokenizer.py` | [`chapter03/section2.lisp`](chapter03/section2.lisp) |
| `notebooks/chapter03/train_gpt_soseki.ipynb` | [`chapter03/train-gpt-soseki.lisp`](chapter03/train-gpt-soseki.lisp) |

And, inside the code:

| PyTorch | rontolisp |
| --- | --- |
| `nn.Module` subclass | `torch:module` + a `forward` defun (no CLOS — see [`.kb/torch.md`](../../.kb/torch.md)) |
| `self.linear = nn.Linear(...)` | a `:linear` entry in the module's **fields plist**, read back with `torch:field` |
| `nn.ModuleList([...])` | a plain LIST in a field; `torch:parameters` recurses into it |
| `nn.ReLU()` inside `nn.Sequential` | `(function torch:relu)` — `torch:forward` applies a bare function too |
| `register_buffer("pe", pe)` | a raw `linalg` array in a field: it is not a parameter, so nothing collects or trains it |
| `torch.bmm(q, k.transpose(1, 2))` | `(torch:matmul q (torch:transpose k '(0 2 1)))` |
| `score.masked_fill(mask, -inf)` | `(torch:masked-fill score mask *neg-infinity*)` |
| `torch.optim.Adam(model.parameters())` | `(torch:adam model)` — an optimizer takes a module directly |
| `torch.nn.utils.rnn.pad_sequence` | `torch:pad-sequence` (always batch-first) |
| `DataLoader(..., shuffle=True)` | `torch:shuffled-batches` — a batch is an ordinary list |
| `@torch.inference_mode` | `torch:no-grad` |
| `nn.GELU()` | `(function torch:gelu)` — exact by default, `:approximate :tanh` for the GPT form |
| `torch.triu(ones(T, T), diagonal=1).bool()` | `(torch:subsequent-mask T)` — already `(1 T T)`, so it broadcasts over the batch |
| `self.apply(self._init_weights)` | a walk over `torch:fields`, dispatching on `torch:module-kind` |
| `AdamW(groups, betas=(0.9, 0.95))` | two `torch:adamw` optimizers over disjoint parameter lists |
| `clip_grad_norm_(params, 1.0)` | `torch:clip-grad-norm` — returns the norm it measured |
| `torch.topk(logits, k)` | `torch:topk` (values, or `:indices t` — one of the pair) |
| `torch.multinomial(probs, 1)` | `torch:multinomial` — the seeded generator, so a SAMPLE reproduces |

## Running

`load` resolves relative to the file doing the loading, so a program runs from
any directory. On all four backends:

```bash
cd chapter02
rontolisp section2.lisp                                     # interpreter
rontolisp section2.lisp -o Prog.class && java Prog          # JVM
rontolisp section2.lisp -o prog.wasm && wasmtime run -W gc prog.wasm
rontolisp section2.lisp -o comp.wasm --component && wasmtime run -W gc=y comp.wasm
```

Chapter 3 is the same, from `chapter03/` (or `gpt/` for `shapes.lisp`).

The output is identical on every backend. Weight initialization, dropout masks,
the epoch shuffle **and the top-k sampling of chapter 3** all draw from the
seeded `linalg` generator, whose arithmetic is integer and therefore
bit-identical everywhere; the printed floats are rounded to a few decimals so
the low-order digits of the WASM `exp`/`log` approximations cannot show
through. That is why the two generated 漱石 passages are the same text on the
interpreter, the JVM and wasm-GC rather than merely the same kind of text.

## `--simd`

Every operation here computes through `linalg`, so
[`--simd`](../../doc/en/guides/simd-acceleration.md) applies with nothing to
change in the source, and it leaves the output byte-identical. The two training
programs are tested with the flag for that reason — it buys back the margin
their interpreter leg needs:

| | scalar | `--simd` |
| --- | --- | --- |
| `chapter02/section5.lisp`, interpreter | 40.9 s | 5.4 s |
| `chapter03/train-gpt-soseki.lisp`, interpreter | 52.4 s | 6.5 s |
| `chapter03/train-gpt-soseki.lisp`, JVM | 2.11 s | 0.70 s |
| `chapter03/train-gpt-soseki.lisp`, wasm-GC | 1.83 s | 0.41 s |

(Median of 3 / min of 5 on an aarch64 DGX Spark, GraalVM 25, wasmtime 47.)
The batched (rank >= 3) matrix product an attention layer almost entirely *is*
became part of the accelerated set on 2026-08-20; before that it was the one
gap that held these programs to a ~1.6x flag, well under what `--simd` gives an
MLP. See
[Accelerating linalg](../../doc/en/guides/simd-acceleration.md#accelerating-linalg).

## `--gpu`

The same batched product is what
[`--gpu`](../../doc/en/guides/simd-acceleration.md#accelerating-the-matrix-product-and-the-transcendentals-on-a-gpu---gpu)
routes to an NVIDIA device, along with the element-wise transcendentals
(`exp`, `tanh`, `erf` and nine more) that `gelu`, `softmax` and `log-softmax`
are built from, and -- since 2026-08-21 -- the broadcast `sub` / `div` / `mul`,
the `:axis` reductions and the axes `transpose` that the rest of `softmax` and
`layer-norm` are made of. **At the shapes tested here it changes nothing**, and that is
the intended answer: a stack of `4 x 8x8` products is a few thousand
multiply-adds and an activation is a few hundred elements, both far under the
thresholds a 15 us round trip has to clear, so every call declines and the
output stays byte-identical with the flag and without it.

It is the shapes in the next section that the flag is for. With
`chapter03/train-gpt-soseki.lisp` raised to the notebook's own `*n-embd*` 384 and
`*block-size*` 256 -- the one-line change the file describes -- a training step
on the JVM class output runs **three to four times faster** with `--gpu --simd`
than with `--simd` alone (0.89 s -> 0.21 s per step, same machine as above, from
a 5-step and a 40-step run so setup and sampling fall out of the slope; the same
program varies by ~15% run to run, so read the ratio rather than the digits).
**What is left of that step is no longer `linalg`**: about a third of it is the
AdamW parameter update and another seventh the dropout random numbers, both
per-element loops written in Lisp inside `torch:` and on no acceleration seam.
Keeping arrays resident on the device between calls -- the obvious next idea --
was measured against this program and declined: every device copy in the step is
under 2% of it.

Note that once a transcendental runs on the device, a program that touches one
is no longer byte-identical with the flag and without it: the device carries its
own `exp` and `erf`, which differ from the CPU's in the last digit or two, and
over 20 training steps that is enough to move the sampled text. The guide's
precision section has the measured divergence; `CUDA_VISIBLE_DEVICES=` makes any
flagged run identical to an unflagged one again.

## The shapes: the book's, and the ones that are tested

The notebook trains a `d_model` = 512, 6-block, 8-head Transformer for 20
epochs over `small_parallel_enja` on a GPU. That is the **documented**
configuration, and this port would run it unchanged. What is actually tested is
a shrunken one, because the point is that the pipeline is right, not that a
laptop can train a translator:

| | book | tested here |
| --- | --- | --- |
| corpus | `small_parallel_enja`, cloned at run time | 8 sentence pairs, in `section5.lisp` itself |
| `d_model` | 512 | 8 |
| blocks / heads | 6 / 8 | 1 / 2 |
| feed-forward width | 512 | 16 |
| section 2.3.3 feed-forward | 512 → 2048 → 512 | 64 → 256 → 64 |
| section 2.3.4 identity training | 10000 × 10, 100 epochs | 64 × 10, 40 epochs |

Every one of those is a `defparameter` at the top of its file: raise them (and
add data) to walk back toward the book's run. The trained model **memorises**
its eight pairs — it reproduces all eight target sentences exactly, and it does
not generalise beyond them. That is what a corpus this small can do, and saying
so is more useful than pretending otherwise.

Chapter 3's notebook trains a `n_embd` = 384, 6-layer, 6-head GPT for 5000
steps over the whole of 『吾輩は猫である』 on a T4, and the same applies:

| | book | tested here |
| --- | --- | --- |
| corpus | the novel, downloaded from 青空文庫 | its opening (448 characters), in `train-gpt-soseki.lisp` itself |
| `block_size` | 256 | 8 |
| `n_embd` | 384 | 8 |
| layers / heads | 6 / 6 | 1 / 2 |
| batch size | 64 | 4 |
| steps | 5000 | 100 |
| generated tokens | 200 | 30 |

100 steps over 448 characters is enough to make the point and no more: the
training loss falls from `4.93` — which is `log(138)`, the uniform guess over
the 138 distinct characters — to about `2.99`, and the samples come out as
recognisable 漱石 fragments (`である。`, `というもの`, `の顔`) strung together
without sentences. Raise the numbers above and the same program keeps going.

## The two places this port deliberately differs from the book

Both are in [`gpt/trainer.lisp`](gpt/trainer.lisp), and both would otherwise
carry a defect across rather than a design:

- **The warmup is applied, not merely printed.** The book's `get_lr` returns
  `base * step / warmup_steps` for the log line, but nothing writes it back —
  its `CosineAnnealingLR` only starts stepping after the warmup, so the
  optimizer runs the whole warmup at the base rate and the schedule it prints is
  not the schedule it trains with. Here `gpt-trainer-lr` is the single answer
  and the loop writes it into both optimizers.
- **`forward(idx, targets=None)` splits in two.** It returns the
  `(logits, loss)` tuple in Python; here `gpt-forward` answers the logits and
  `gpt-loss` the loss, because a forward whose result shape depends on whether
  an optional argument was passed is a tuple only Python's caller destructures
  cheaply.

Two more differences are the port's, not the book's, and are noted where they
happen: `nn.Module.apply` becomes a walk over `torch:fields` dispatching on
`torch:module-kind` (what a layer *is*, rather than a substring of its dotted
parameter name), and the elapsed-time column of the training log is dropped —
it is the one number that cannot come out the same on four backends.

## Sizes

Compiled artifact sizes are measured, not quoted here: see
[`size-report/results/`](../../size-report/results/).
