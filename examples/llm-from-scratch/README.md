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

## The shapes: the book's, and the ones that are tested

The notebook trains a `d_model` = 512, 6-block, 8-head Transformer for 20
epochs over `small_parallel_enja` on a GPU. This port runs that configuration
unchanged (see [Performance](#performance)); what is tested is a shrunken one,
because the point of the test is that the pipeline is right:

| | book | tested here |
| --- | --- | --- |
| corpus | `small_parallel_enja`, cloned at run time | 8 sentence pairs, in `section5.lisp` itself |
| `d_model` | 512 | 8 |
| blocks / heads | 6 / 8 | 1 / 2 |
| feed-forward width | 512 | 16 |
| section 2.3.3 feed-forward | 512 → 2048 → 512 | 64 → 256 → 64 |
| section 2.3.4 identity training | 10000 × 10, 100 epochs | 64 × 10, 40 epochs |

Every one of those is a `defparameter` at the top of its file: raise them (and
add data) to walk back toward the book's run. At the tested size the model
**memorises** its eight pairs — it reproduces all eight target sentences exactly
and does not generalise beyond them.

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
training loss falls from `4.93` — `log(138)`, the uniform guess over the 138
distinct characters — to about `2.99`, and the samples come out as recognisable
漱石 fragments (`である。`, `というもの`, `の顔`) strung together without sentences.

## Performance

Everything here computes through `linalg`, so the acceleration flags apply with nothing
to change in the source -- [`--simd`](../../doc/en/guides/simd-acceleration.md),
[`--parallel`](../../doc/en/guides/simd-acceleration.md#using-more-than-one-core---parallel),
[`--blas`](../../doc/en/guides/blas-acceleration.md),
[`--gpu`](../../doc/en/guides/gpu-acceleration.md) -- and the output stays the same
except where a device transcendental runs (`--gpu`: its `exp` / `erf` differ from the
CPU's in the last digit or two, which over a training run moves the sampled text;
`CUDA_VISIBLE_DEVICES=` makes the run identical again). The history of how each number
below was reached is in [`.kb/gpu.md`](../../.kb/gpu.md); this section keeps the current
figures only. All on one aarch64 DGX Spark (GB10, 20 cores, 128 GB unified memory),
GraalVM 25, wasmtime 47, JVM class output unless noted; medians of three runs, 2026-08-23.

**The shapes that are tested** (the shrunken ones in the files, below):

| | scalar | `--simd` |
| --- | --- | --- |
| `chapter02/section5.lisp`, interpreter | 40.9 s | 5.4 s |
| `chapter03/train-gpt-soseki.lisp`, interpreter | 52.4 s | 6.5 s |
| `chapter03/train-gpt-soseki.lisp`, JVM | 2.11 s | 0.70 s |
| `chapter03/train-gpt-soseki.lisp`, wasm-GC | 1.83 s | 0.41 s |

`--gpu` changes nothing at these shapes, by design: a stack of `4 x 8x8` products is a
few thousand multiply-adds, far under the threshold a 15 us round trip has to clear, so
every call declines and the output is byte-identical with the flag and without it.

**`train-gpt-soseki.lisp` at the notebook's width** (`*n-embd*` 384, `*block-size*` 256,
the rest as in the file), per training step:

| flags | per step, `(t40 - t5) / 35` | steady state, `(t200 - t40) / 160` |
| --- | --- | --- |
| `--simd` | 0.79 s | 0.85 s |
| `--simd --parallel` | 0.37 s | -- |
| `--blas --simd` | 0.79 s | -- |
| `--gpu --simd` | **0.050 s** | **0.016 s** |

`--blas` changes nothing because every product here is the stacked rank-3 one, which
`--blas` does not take; `--parallel` halves the CPU step because that product is what it
splits, and adds nothing to a `--gpu` run, where the device takes it first. On an Apple M4
Max the same program runs at 0.104 s a step under `--gpu --simd` against 0.70 under
`--simd` ([the guide's Apple section](../../doc/en/guides/gpu-acceleration.md#on-apple-silicon)).
The interpreter shows no change from `--gpu` at any of these shapes: its step is 30x a
compiled one, and what dominates it is the tree walk, not the kernels -- compile before
you measure a flag.

**The book's own shapes**, run unchanged but for data (the whole novel, fetched from
青空文庫 and stripped of ruby beforehand; a cloned `small_parallel_enja`) and the
`defparameter`s at the top of each file set to the tables above. `--gpu --simd`, JVM
class output, `java -Xmx64g` plus the collector each program's shape asks for -- chapter 3
`-XX:+UseParallelGC -Xmn8g`, chapter 2 `-XX:+ExplicitGCInvokesConcurrent` (why those
flags, and why they differ: below):

| | | rontolisp | PyTorch on the same machine |
| --- | --- | --- | --- |
| chapter 3, 13.06 M parameters | per training step | **0.81 s** | 0.24 s eager fp32 (TF32 tensor cores, the container's default), 0.21 s bf16 autocast, 0.096 s `torch.compile` + bf16 |
| | the notebook's 5000 steps (warmup 1000, eval every 500) | **67.2 min**; loss 8.10 -> 0.24, validation 0.116 | 20.4 min eager fp32; loss 0.17 |
| chapter 2, `d_model` 512 | per batch of 64 pairs | **0.30 s** | -- |
| | 2 epochs over 10000 pairs + 20 greedy decodes | **1.7 min**; loss 4.72 -> 3.61 | -- |
| | the notebook's 20 epochs over 50000 pairs (15640 batches) | **89 min**; loss 3.56 -> 0.052, 14 of 20 sentences translated exactly | -- |

The PyTorch column is the same model (the book's per-head attention, AdamW, clipping,
dropout 0.1) written against PyTorch in NVIDIA's `pytorch:25.11` container on the same
machine, 300 steps, `(t200 - t40) / 160`; `torch.compile` adds 19 s of compilation. So
the port is about 3x behind eager PyTorch on this card and 8x behind the compiled bf16
run -- and a profile of both runs says where, precisely. Both steps are DEVICE-bound
(rontolisp ~0.72 s of kernel time in its step, PyTorch ~0.23 in its): the difference is
what the device is asked to do. PyTorch's "eager fp32" products run on TF32 **tensor
cores** (the container's default) for ~98 ms a step where rontolisp's bit-exact IEEE
f32 product takes ~250 ms; and PyTorch's elementwise ops are fused single kernels
(dropout, softmax, layer-norm, GELU each one memory pass) for ~133 ms where rontolisp
pays one pass per `linalg:` member, ~475 ms. Launches are not the story: since
2026-08-23 the launch pipeline runs ahead of the device (`.kb/gpu.md`) and the host is
overlapped. The 5000-step
run memorises the novel (the validation loss is over windows that overlap the training
windows, the book's own `random_split`) and samples sentence-shaped 漱石:
`吾輩はこのくらいの家アンドレア・デル・サルトでもこれである。美学者は笑いながら…`.

**Chapter 2's batch is a quarter host; chapter 3's is not.** The 20-epoch run ends at a
training loss of 0.052 and translates 14 of the first 20 source sentences exactly
(`私 は テニス 部員 で す 。` -> `i 'm in the tennis club .`,
`道路 を 横切 る とき は 車 に 注意 し なさ い 。` -> `when you cross the street , watch out
for cars .`). Its batch is 0.32 s of which 230 ms is device kernel time -- **11029 kernel
launches** against chapter 3's 3830 in a step twice as long, because this port follows the
book's explicit per-head loop: 1011 parameter tensors, 2289 batched products and 2158 axis
folds a batch, most of them small. What leaves the device idle for the rest is not a host
READ (four downloads a batch, 4 MB -- results stay on the device) but a host WRITE: **247
MB staged UP a batch**, and an upload waits for the queued kernels before it copies. Of
that, 183 MB is an array of ZEROS, allocated by the reduction adjoint so that a broadcast
add will stretch the gradient back over it; the one member that genuinely runs on the CPU
is the positional encoding's mixed-width add, which
[`transformer/utils.lisp`](transformer/utils.lisp) explains and chooses deliberately.
[`.kb/gpu.md`](../../.kb/gpu.md) has the profile.

**One rule, not two flag sets.** `--gpu` keeps results on the device, so a result the
program has dropped holds device memory until the collector notices it is gone; the
library asks for a collection when its budget fills, and REFUSING that request
(`-XX:+DisableExplicitGC`) makes the book's shapes **4.5x** slower. Granting it is cheap:
about 50 ms a collection and 3% of a run, whichever collector answers. What is not cheap
is what the answer does to the heap's PAGES -- a device copy to or from a page the GPU has
never touched costs about a hundred times a warm one -- so what `--gpu` wants is a heap
whose pages the program keeps recycling. Two ways to have one, and they are why the flags
above are what they are: the parallel collector with a young generation the program FILLS
(a step here allocates ~1.9 GB, so `-Xmn8g` turns over every four steps), which is the
fastest measured -- the 5000 steps in 67.2 min; or the default collector told never to
compact on request, `-XX:+ExplicitGCInvokesConcurrent`, which is 15% faster than the
default collector alone (103 steps in 99 s against 115) and 7% behind the row above over
the 5000 (72.2 min). The trap is the middle one: a young generation the program never
fills is gigabytes of pages the device has never touched, and `-Xmn4g` at the notebook's
width is **57% slower** than setting nothing at all. So hand-size a young generation only
where the program fills it, and otherwise leave the collector alone -- at the notebook's
width, where the budget is never reached and no collection is ever asked for, none of this
applies. That is why chapter 2 above is run under different flags from chapter 3: it
allocates less a batch and never fills an 8 GB young generation, so the concurrent answer
wins there -- by 5% over 2 epochs and 4% over the full 20 (89.4 min against 92.9), with
byte-identical output either way. `.kb/gpu.md` has the logs.

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
