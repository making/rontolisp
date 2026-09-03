# llama2.c in rontolisp

[Andrej Karpathy's llama2.c](https://github.com/karpathy/llama2.c) `run.c`,
ported whole to one Lisp file: the checkpoint loader, the SentencePiece-style
tokenizer with its BPE encoder, the Llama 2 forward pass (RMSNorm, RoPE,
multi-head causal attention over a KV cache, SwiGLU, the classifier head), the
temperature / top-p sampler with run.c's own xorshift generator, and the
generate loop. Given a checkpoint the C program reads, it tells the same
stories -- token for token, at temperature 0 and at any seed. The forward pass
is written as a [table of layer kinds](#the-layer-table), so a family that
differs from Llama 2 is a row of options rather than a fork of the file.

The 1 MB `stories260K.bin` + `tok512.bin` pair is checked in (from
[karpathy/tinyllamas](https://huggingface.co/karpathy/tinyllamas), MIT). The
model the llama2.c README demos, `stories15M.bin` (60 MB), is one script away:

```bash
./download-stories15M.sh          # stories15M.bin + tokenizer.bin, into this directory
```

## Running

The knobs are run.c's own flags, read with `uiop:command-line-arguments`. Each
one falls back to an `LLAMA2_*` environment variable, for a host that hands the
program no command line (a browser shim, an embedder):

| run.c flag | variable | default |
| --- | --- | --- |
| the positional checkpoint | `LLAMA2_CHECKPOINT` | `stories15M.bin` |
| `-z` | `LLAMA2_TOKENIZER` | `tokenizer.bin` |
| `-i` | `LLAMA2_PROMPT` | empty |
| `-n` | `LLAMA2_STEPS` | 256 |
| `-t` | `LLAMA2_TEMPERATURE` | 1.0 (0 = greedy) |
| `-p` | `LLAMA2_TOPP` | 0.9 |
| `-s` | `LLAMA2_SEED` | the clock |

From this directory, on all four backends. The interpreter takes the program's
own arguments after `--` (everything before it is the compiler's); a compiled
artifact takes them straight after itself:

```bash
ARGS='stories15M.bin -t 0 -i "Once upon a time"'

rontolisp llama2.lisp --simd -- $ARGS                            # interpreter
rontolisp llama2.lisp -o Prog.class --simd && \
  java --add-modules jdk.incubator.vector Prog $ARGS
rontolisp llama2.lisp -o Prog.class --gpu --simd && \
  java --enable-native-access=ALL-UNNAMED --add-modules jdk.incubator.vector Prog $ARGS  # + an NVIDIA GPU
rontolisp llama2.lisp -o llama2.wasm --simd && \
  wasmtime run --dir . llama2.wasm $ARGS
rontolisp llama2.lisp -o llama2.wasm --simd --component && \
  wasmtime run --dir . llama2.wasm $ARGS
```

Every one of them prints

```
Once upon a time, there was a little girl named Lily. She loved to play outside in the sunshine. One day, she saw a big, red ball in the sky. It was the sun! She thought it was so pretty.
Lily wanted to play with the ball...
```

which is what `./run stories15M.bin -t 0 -i "Once upon a time"` prints -- the
whole 256-token story is byte-identical, and so is the command line. The small
model runs the same way with `stories260K.bin -z tok512.bin`.

## The layer table

The one thing here that is not `run.c`: the forward pass is a **table of layer
kinds**, not Llama 2 spelled out. A model is a list of layers, every one of them
the same residual sandwich

```
x <- x + residual-multiplier * f(rmsnorm(x, the layer's norm), the layer)
```

differing only in what `f` is -- the layer's `:kind` -- and in the options
recorded beside it when the model was loaded:

| option | what varies | who has it |
| --- | --- | --- |
| `:q-norm` / `:k-norm` | RMSNorm over each head's own dims of q and k | Qwen3 |
| `:rope` | `:pairs` (adjacent pairs, what llama2.c's `.bin` and a llama.cpp-converted GGUF of a Llama-family model hold -- the converter permutes Q and K only for those), `:halves` (Hugging Face's `rotate_half`, what a safetensors file and a Qwen GGUF hold), or `nil` -- no rotation at all | SmolLM3 leaves every 4th block unrotated |
| `:rotary-dim` | how many of each head's dims rotate | partial-RoPE models (Qwen3.5: 64 of 256) |
| `:scale` | the attention scale, when it is not `1/sqrt(head-size)` | Granite |
| `:gate` | an output gate over the head outputs, before `wo` | gated attention (Qwen3.5) |
| `:full-attention-interval` | a hybrid: every Nth block is `:attention`, the rest `:deltanet` | Qwen3.5 (4) |
| model-wide | `:rope-theta`, `:eps`, and the embedding / residual / logit multipliers | Granite, and everything since Llama 2 moved `rope_theta` |

`*architectures*` is the other half: one row per `general.architecture` (GGUF) /
`model_type` (`config.json`), holding what that family does differently. A
reader of a published checkpoint looks its file's name up, prepends what the
FILE decides (the RoPE layout, and any per-checkpoint scalar), and hands the
result to `transformer-layers`, which builds the list the forward pass walks.

llama2.c's `.bin` is the row where every option is at its default -- `llama`,
`:rope :pairs` -- so the table degenerates to `:attention` then `:swiglu` per
block, which is Llama 2, and the stories stay byte-identical.

### The Gated DeltaNet layer (Qwen3.5)

Qwen3.5 (and 3.6 / 3.8, the same `qwen35` architecture) puts a gated linear
recurrence in three of every four blocks: per head a 128 x 128 state matrix
`S` that decays a little each token, is corrected toward the current value
along the current key (the delta rule) and is read out along the query --
no KV cache, the state is the whole memory. It is the third `:kind`,
`:deltanet`, and lives in [`deltanet.lisp`](deltanet.lisp): the single-token
path of `transformers`' `modeling_qwen3_5.py` (`causal_conv1d_update`,
`torch_recurrent_gated_delta_rule`, `Qwen3_5RMSNormGated`), with the weights
plist a checkpoint reader hands it documented in the file's header. `S` is
kept transposed so both reads (`k^T S`, `q^T S`) are a `vec:matvec`; the decay
and the rank-1 update are one typed `dotimes` over the state, which the JVM
backend compiles to a primitive loop.

[`deltanet-check.lisp`](deltanet-check.lisp) pins the arithmetic:
[`deltanet-ref.py`](deltanet-ref.py) is the PyTorch reference transcribed
into plain float64 Python over pseudo-random inputs, and the check prints the
same numbers -- the recurrence alone at heads 2 / dim 4 over three tokens
with the final states, then the whole decode step over five tokens of a
dim-8, two-head, kernel-4 layer -- at 3 decimals, none within 2e-5 of a
rounding boundary, identically on the interpreter, the JVM, wasm-GC and the
component, with and without `--simd`.

Where a Qwen3.5-0.8B token's time would go, measured at the real shape with
random weights (18 layers of dim 1024, 16 heads of 128, kernel 4; JVM class
output under `--simd`, ONE thread, f32 weights; commit `594ddac9`, Graal JIT
-- `UseJVMCICompiler` on -- on JDK 25.0.4, a Xeon E5-2697A v4; the numbers
move with `.todo/480`, whose column gate sits exactly at this 128 x 128 GEMV
shape):

| per token | ms |
| --- | --- |
| the 18 Gated DeltaNet mixers, whole | 103 |
| of which the recurrence, 18 x 16 heads (two 128 x 128 `vec:matvec` + the fused decay / rank-1 update over the 128 x 128 state) | 21 |
| of which the causal convolution (6144 channels x 4 taps) | 1.9 |
| of which the projections (f32 GEMVs, 756 MB per token) | 62 |
| for scale: one block's SwiGLU GEMVs x 24, cache-warm | 82 |
| for scale: the tied 248320 x 1024 classifier, f32 | 110 |

So the rank-1 update as a typed loop is about 4 ns per state element and
under a tenth of a token; a `vec:ger-into` kernel is not worth its surface
until the GEMVs shrink under it (bf16 weights halve the GEMV rows above).

## Why `--simd` (and `--parallel`, and `--gpu`)

Decoding is one token at a time, so every matrix in the model multiplies a
vector: the whole forward pass is GEMV (`vec:matvec`), 15 million multiply-adds
per token for stories15M, and `--simd` lowers it to CPU vector instructions;
`--simd --parallel` splits each GEMV's rows across the cores, and `--gpu` moves
the big ones to the device. Measured on this project's NVIDIA GB10 box (aarch64,
10 Cortex-X925 at 3.9 GHz + 10 Cortex-A725, GraalVM 25), the 222-token story
above, every row re-measured together on 2026-08-22 -- medians of three
interleaved runs, nothing pinned:

| backend | threads | scalar | `--simd` | `--simd --parallel` | `--gpu --simd` | `--gpu --simd --parallel` |
| --- | --- | --- | --- | --- | --- | --- |
| JVM | 1, or 20 under `--parallel` | 104 tok/s | 336 tok/s | 637 tok/s (684 with `RONTOLISP_THREADS=10`) | 458 tok/s | 427 tok/s |
| wasm-GC (`wasmtime`) | 1 | 0.4 tok/s | 125 tok/s | -- (no threads) | -- (no FFM) | -- |
| interpreter (`java -jar`) | 1, or 20 under `--parallel` | ~15 s per token | 44 tok/s | 44 tok/s | 42 tok/s | -- |

Without `--parallel` every rontolisp backend decodes on ONE thread (the JVM
still runs its own GC and JIT threads: ~3.1 s of CPU for a 1.4 s run); with it
the GEMVs run on every core and the rest of the token still runs on one. The C
and Java ports of the same program, same box, same story, are the reference:

| reference | threads | tok/s |
| --- | --- | --- |
| `run.c -O2` | 1 | 147 tok/s |
| Java Vector API port of run.c ([kishida's gist](https://gist.github.com/kishida/05656bfcbe840f269784f7dbbee5928e), `-v on`), every `.parallel()` removed | 1 | 312 tok/s |
| the same gist as published, `matmul` and the attention heads being `IntStream.range(...).parallel()` | 20 | 513 tok/s |

So the standing today, stated plainly: **on one thread `--simd` (336) beats
that port (312), and on 20 threads `--simd --parallel` (637) beats the gist as
published (513)**, the same thread count on each row. Before the JVM backend's
typed loops (also 2026-08-22) both rows lost (221 against 297, 319 against 535,
measured the same way): the GEMVs were already at
parity, and the ~2 ms a token this file spent in boxed Lisp around them -- the
softmax, RoPE, attention copies and KV-cache loops, ~60 ns an iteration of
`Double`/`Long` allocation and `Object` dispatch -- was the whole gap. The JVM
backend now compiles a `dotimes` of that shape to a primitive loop
(`.kb/jvm-typed-loops.md`; the same values, ~30x on the softmax), and the GEMV
kernel vectorizes a short row (the 48-wide attention head used to run scalar,
`.kb/vec.md`); nothing in this file changed. `--gpu --simd` (458) and
`--gpu --simd --parallel` (427) both trail `--simd --parallel` now: the device
takes the big GEMVs but pays a synchronous download per call, and with the
spinning worker threads also competing with its driver for the cores the
combination is the slower of the two -- pick `--simd --parallel` for this
program. `--blas` entered the intercepted set on 2026-09-02: `vec:matvec` and
`vec:matvec-into` are a `cblas_?gemv`, so the flag now reaches this program
(`doc/en/guides/blas-acceleration.md`). It is NOT in the table above because it
was not measured on this box -- on a 64-core Xeon E5-2697A v4 with OpenBLAS the
JVM backend decodes stories15M at 102-110 tok/s under `--simd` and 121-124 under
`--simd --blas` with `OPENBLAS_NUM_THREADS=1`, so about 1.15x, with the story
byte-identical at 150 tokens. **Leave the thread cap off and it is a rout**: at
the library's default thread count the same run drops to 16 tok/s, because a
GEMV is short and memory-bound and the per-call thread barrier swamps it.
Two caveats that keep the two tables honest: the gist's `-t 0` decode does NOT
reproduce run.c's story (a different one comes out, so its rows are throughput
only), while every rontolisp row is byte-identical to `./run stories15M.bin -t 0
-i "Once upon a time"`; and an earlier version of this table (JVM 23 / 87,
wasm-GC 0.4 / 46, `run.c` 65, the gist 100 / 187) was measured on 2026-08-19 on
a different, 64-core x86 box -- those numbers must not be compared with the rows
above.

The `--simd` lane kernel streams the 60 MB of weights at ~20 GB/s, about 2.4 ms
of a 3.0 ms token on one JVM thread; what is left is the attention's 72 small
GEMVs and the kernel calls between them (`.todo/480` names the next lever: the
GEMV row is one accumulator chain). `--simd --parallel` runs
every GEMV above ~2^15 multiply-adds -- all of them here, the 288x288
projections included -- over a row range per thread, bit-identical to the
serial kernel ([the guide](../../doc/en/guides/simd-acceleration.md#using-more-than-one-core---parallel));
`RONTOLISP_THREADS=10` is slightly better than the default 20 on this box because
the second ten cores are the small ones. `--gpu --simd` moves the GEMVs whose matrix is big enough and
STAYS on the device -- the three feed-forward matrices per layer and the
classifier head, two thirds of the multiply-adds; the 288x288 projections are a
tie at ~12 us and stay on the CPU -- from their second token on, once the
library has seen the weight twice unwritten ([the guide](../../doc/en/guides/gpu-acceleration.md)).
That is about 1.4x over `--simd` with the story unchanged, and below
`--simd --parallel` on this box. On the interpreter neither flag buys anything (44 tok/s
under `--simd --parallel`, 42 under `--gpu --simd`): the tree walk around the
GEMVs dominates there. On an Apple M4 Max the JVM decodes the
same story at ~370 tok/s under `--simd` and at the same ~370 under `--gpu --simd`,
story unchanged: only the classifier head is above Metal's threshold there, and
the one GEMV per token it moves pays the GPU's idle-clock penalty after the
2.7 ms of CPU work between tokens ([the guide](../../doc/en/guides/gpu-acceleration.md#on-apple-silicon)). The `--simd` kernel's deliberately pinned
128-bit accumulation (one chain per row, so results agree bit for bit with the
WASM `f32x4` kernels on every host) is what the device does NOT reproduce -- it
accumulates in double, like the scalar `vec.lisp` definition, and lands on that
definition's bits instead.

The interpreter's `--simd` needs the native binary or
`java --add-modules jdk.incubator.vector -jar ...`; without the Vector API it
runs the scalar `vec.lisp` kernels, one interpreted form per multiply-add,
which is fine for stories260K and not for stories15M.

The checkpoint's 15 million little-endian `float32`s load in about 0.2 s on
every backend: `read-sequence` over a packed single-float array reads raw
IEEE-754 elements in bulk, one transfer per weight matrix, so the loader is a
`make-array` and a `read-sequence` per tensor.

[`../ml/tiny-llm.lisp`](../ml/tiny-llm.lisp) is the arithmetic core of this
file with the I/O taken away, and explains the KV-cache layout (keys row-major,
values transposed) that makes both halves of attention a GEMV.
