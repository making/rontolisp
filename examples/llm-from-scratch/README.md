# LLM from Scratch — the Transformer chapter, in rontolisp

A rontolisp port of chapter 2 of [**『作ってわかる大規模言語モデルの仕組み』**
(Elith Inc., Nikkei BP, 2026) — its sample
repository](https://github.com/elith-co-jp/book-llm-from-scratch): the reusable
`llm_from_scratch/transformer/` package and the chapter 2 notebooks (sections
2.2 - 2.5). Nothing from that repository is vendored here; this is a rewrite of
its PyTorch code, which maps onto the [`torch`
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

The output is identical on every backend. Weight initialization, dropout masks
and the epoch shuffle all draw from the seeded `linalg` generator, whose
arithmetic is integer and therefore bit-identical everywhere; the printed
floats are rounded to a few decimals so the low-order digits of the WASM
`exp`/`log` approximations cannot show through.

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

## Sizes

Compiled artifact sizes are measured, not quoted here: see
[`size-report/results/`](../../size-report/results/).
