# Checkpoint readers: the `checkpoint` staging package and `safetensors:read`

**Invariant: a published checkpoint's tensors land in PACKED float arrays, staged in
bounded chunks, through a file walked front to back -- and the reader answers the same
arrays on every backend with and without `--simd`.** Introduced by `.todo/675`
(2026-09-03) as the reader half of `.todo/670` (run a Hugging Face model from the
downloaded file); the GGUF reader (`.todo/673`) is written over the same `checkpoint`
package.

## The two packages

- `checkpoint` (`src/main/resources/am/ik/rontolisp/eval/checkpoint.lisp`,
  `eval.CheckpointLibrary`): `make-tensor`, `stage-float-bits`, `stage-float32`,
  `skip-bytes`. The staging every format shares.
- `safetensors` (`safetensors.lisp`, `eval.SafetensorsLibrary`): `read`, `header`,
  `entries`. The Hugging Face format over it.

Both are Lisp-source libraries in the `geom` shape (`.kb/geom.md`): the interpreter
evaluates a library on the first resolution of a qualified function
(`LispEvaluator#resolveFunction`), the compile path splices it when the program
mentions the package (`CompileFrontend`: `SafetensorsLibrary.process` first,
`CheckpointLibrary.process` second, so the `checkpoint:` references inside a spliced
reader pull the staging in, and both BEFORE `JsonLibrary` and the prelude, which
supply the `rontolisp:json-parse` and `%octets-to-string` the definitions reach for),
and every defun is prunable (`LibraryDefunPruner`). Registered in `PackageRegistry`
(`CHECKPOINT_FUNCTIONS` / `SAFETENSORS_FUNCTIONS`, no `cl` use, every name external --
`safetensors:read` is the package's own `READ`) and in the native image's
`resource-config.json`.

## Three facts the design rests on

1. **`file-position` answers nil on every backend** (`.todo/390`): a stream cannot
   seek. So a reader WALKS its file front to back in tensor-offset order and passes
   over a tensor it was told not to load with `checkpoint:skip-bytes` -- bounded reads
   through a 64 KB scratch buffer, never a staging of what is skipped. A sharded
   checkpoint (`model.safetensors.index.json`) opens each shard that holds a wanted
   tensor exactly once and walks it once, whatever `:only` keeps. A checkpoint is read
   whole anyway, so the walk costs nothing a seek would have saved; when `.todo/390`
   lands, the skip can become a seek.
2. **A packed `(unsigned-byte 16)` vector costs 8 bytes an element** on the interpreter
   and the JVM (`LispIntVector` is a `long[]`, `.kb/binary-sequence-io.md`), so a bf16
   tensor staged whole would cost FOUR times its file size in temporaries -- 8.8 GB
   for a 1.1B-element tensor. `checkpoint:stage-float-bits` therefore takes the STREAM
   and reads it in chunks of 1M elements (2 MB on disk, 8 MB in memory) through one
   buffer reused for every tensor of every file, widening each chunk into the
   destination with `widen-float-bits ... :start` (`.todo/671`); the last, shorter
   chunk goes through a buffer of its own size because `widen-float-bits` widens the
   whole vector it is handed. Handing the stream over, not a staged vector, is what
   makes it impossible for a caller to get the chunking wrong.
3. **`make-array :element-type` does not signal on an element type it does not know**
   -- it answers a boxed general array (`.todo/683`), and `vec:zeros`'s width dispatch
   is an `eq` test with a double default. A 1.5 GB checkpoint read into a boxed array
   would only show as slow, wrong output much later, so `checkpoint:make-tensor` is
   the ONE allocation path and asserts `(array-element-type a)` is the type it asked
   for. A `#bf16` destination (`.todo/484` / `.todo/485`) is added there when it
   exists; today the bf16 / f16 file widths widen into `#f` (or `#d`).

## The safetensors format, as read

`u64` little-endian header length N; N bytes of JSON -- `{ "<name>": { "dtype":
"BF16", "shape": [rows, cols], "data_offsets": [begin, end] }, ..., "__metadata__":
{...} }` -- then the tensor bytes at their offsets from the end of the header,
row-major. `safetensors:header` answers the parsed JSON (a string-keyed hash table,
`.kb/json.md`) and the data start; `safetensors:entries` the tensor infos as `(name
dtype shape begin end)` sorted by `begin`. `safetensors:read` walks those, building
each tensor with `checkpoint:make-tensor` of the file's shape (a rank-1 vector for a
one-dimensional shape, a rank-N packed array otherwise -- a `[c, 1, k]` conv weight
comes back rank 3, and the caller squeezes it) in the `:element-type` asked for:
F32 by one `read-sequence` (through a single-float array when the destination is
double), F16 and BF16 through `stage-float-bits`; every other dtype is an error
naming the tensor and the dtype (`safetensors: ids is I64; supported dtypes: F32,
F16, BF16`). `:only` is a predicate over the name -- a prefix filter is a lambda --
so a multimodal checkpoint's vision tower and speculative head cost their bytes of
I/O and nothing else. A directory argument probes `model.safetensors.index.json`
then `model.safetensors`; an `.index.json` reads its shards; a `.safetensors` reads
itself.

## What a reader does NOT do

The name mapping to a model's plist -- HF's `model.layers.N.self_attn.q_proj.weight`
into `llama2.lisp`'s `:wq`, Qwen3.5's `1 + w` norms, `-exp(A_log)`, the `query |
gate` interleave, LFM2's `operator_norm` -- is the consumer's (`examples/llama2/
llama2.lisp`, `load-hf-checkpoint`), because it is per family and the reader is per
FORMAT. The facts each family needs are recorded in `.todo/673` / `.todo/675` and in
the example's architecture table.

## Verification

- `examples/llama2/safetensors-check.lisp` over `safetensors-check.safetensors` (a
  few hundred bytes, written by `safetensors-fixture.py` beside it: F32 / F16 / BF16
  tensors whose values are exact in every width, a rank-1 and a rank-2 shape, a
  `__metadata__`, an I64 tensor to refuse, and a two-shard pair with its index): the
  header, every dtype into single and double floats, the refusal, `:only` skipping
  the I64, the sharded index, a prefix filter. Pinned text in
  `examples/.expected/safetensors-check.txt`, `examples.yaml`. The compiled legs need
  `.todo/671`'s JVM / WASM arms of `widen-float-bits`, so the entry runs the
  interpreter leg alone until those land.
- The real thing: TinyLlama-1.1B-Chat's BF16 `model.safetensors` (2.2 GB) through
  `examples/llama2/llama2.lisp` with llama2.c's Llama 2 `tokenizer.bin` (the same
  32000-entry vocabulary) -- see the example's README for the text and the numbers.
