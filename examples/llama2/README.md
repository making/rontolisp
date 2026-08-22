# llama2.c in rontolisp

[Andrej Karpathy's llama2.c](https://github.com/karpathy/llama2.c) `run.c`,
ported whole to one Lisp file: the checkpoint loader, the SentencePiece-style
tokenizer with its BPE encoder, the Llama 2 forward pass (RMSNorm, RoPE,
multi-head causal attention over a KV cache, SwiGLU, the classifier head), the
temperature / top-p sampler with run.c's own xorshift generator, and the
generate loop. Given a checkpoint the C program reads, it tells the same
stories -- token for token, at temperature 0 and at any seed.

The 1 MB `stories260K.bin` + `tok512.bin` pair is checked in (from
[karpathy/tinyllamas](https://huggingface.co/karpathy/tinyllamas), MIT). The
model the llama2.c README demos, `stories15M.bin` (60 MB), is one script away:

```bash
./download-stories15M.sh          # stories15M.bin + tokenizer.bin, into this directory
```

## Running

The knobs are run.c's flags, read from the environment (a rontolisp program has
no argv yet):

| variable | run.c flag | default |
| --- | --- | --- |
| `LLAMA2_CHECKPOINT` | the positional checkpoint | `stories15M.bin` |
| `LLAMA2_TOKENIZER` | `-z` | `tokenizer.bin` |
| `LLAMA2_PROMPT` | `-i` | empty |
| `LLAMA2_STEPS` | `-n` | 256 |
| `LLAMA2_TEMPERATURE` | `-t` | 1.0 (0 = greedy) |
| `LLAMA2_TOPP` | `-p` | 0.9 |
| `LLAMA2_SEED` | `-s` | the clock |

From this directory, on all four backends:

```bash
export LLAMA2_PROMPT="Once upon a time" LLAMA2_TEMPERATURE=0

rontolisp llama2.lisp --simd                                    # interpreter
rontolisp llama2.lisp -o Prog.class --simd && java --add-modules jdk.incubator.vector Prog
rontolisp llama2.lisp -o Prog.class --gpu --simd && \
  java --enable-native-access=ALL-UNNAMED --add-modules jdk.incubator.vector Prog   # + an NVIDIA GPU
rontolisp llama2.lisp -o llama2.wasm --simd && \
  wasmtime run -W gc --dir . --env LLAMA2_PROMPT --env LLAMA2_TEMPERATURE llama2.wasm
rontolisp llama2.lisp -o llama2.wasm --simd --component && \
  wasmtime run -W gc --dir . --env LLAMA2_PROMPT --env LLAMA2_TEMPERATURE llama2.wasm
```

Every one of them prints

```
Once upon a time, there was a little girl named Lily. She loved to play outside in the sunshine. One day, she saw a big, red ball in the sky. It was the sun! She thought it was so pretty.
Lily wanted to play with the ball...
```

which is what `./run stories15M.bin -t 0 -i "Once upon a time"` prints -- the
whole 256-token story is byte-identical. The small model runs the same way with
`LLAMA2_CHECKPOINT=stories260K.bin LLAMA2_TOKENIZER=tok512.bin`.

## Why `--simd` (and `--gpu`)

Decoding is one token at a time, so every matrix in the model multiplies a
vector: the whole forward pass is GEMV (`vec:matvec`), 15 million multiply-adds
per token for stories15M, and `--simd` lowers it to CPU vector instructions.
Measured on this project's NVIDIA GB10 box (aarch64, 10 Cortex-X925 at 3.9 GHz +
10 Cortex-A725, GraalVM 25), the 222-token story above, every row re-measured
together on 2026-08-22 -- medians of three interleaved runs, nothing pinned:

| backend | scalar | `--simd` | `--gpu --simd` |
| --- | --- | --- | --- |
| JVM | 66 tok/s | 218 tok/s | 283 tok/s |
| wasm-GC (`wasmtime`) | 0.4 tok/s | 128 tok/s | -- (no FFM) |
| interpreter (`java -jar`) | ~15 s per token | 43 tok/s | 42 tok/s |

Every rontolisp backend above decodes on ONE thread (the JVM still runs its own
GC and JIT threads: ~3.1 s of CPU for a 1.4 s run). The C and Java ports of the
same program, same box, same story, are the reference:

| reference | threads | tok/s |
| --- | --- | --- |
| `run.c -O2` | 1 | 147 tok/s |
| Java Vector API port of run.c ([kishida's gist](https://gist.github.com/kishida/05656bfcbe840f269784f7dbbee5928e)), the `.parallel()` in `matmul` removed | 1 | 297 tok/s |
| the same gist as published, `matmul` being `IntStream.range(0, d).parallel()` | 20 | 535 tok/s |

So the standing today, stated plainly: **on one thread `--simd` (218) loses to
that port (297)**, by the ~1.2 ms a token this file spends in boxed Lisp around
the GEMVs (`.todo/457`); `--gpu --simd` (283) does not catch it either; and the
gist as published is a multi-core program rontolisp has no answer to at all,
since no backend here uses more than one thread for a kernel (`.todo/478`).
`--blas` does not enter this table: `vec:matvec` is outside its intercepted set
(`.todo/471`), so the flag does nothing for this program today. Two caveats that
keep the two tables honest: the gist's `-t 0` decode does NOT reproduce run.c's
story (a different one comes out, so its rows are throughput only), while every
rontolisp row is byte-identical to `./run stories15M.bin -t 0 -i "Once upon a
time"`; and an earlier version of this table (JVM 23 / 87, wasm-GC 0.4 / 46,
`run.c` 65, the gist 100 / 187) was measured on 2026-08-19 on a different,
64-core x86 box -- those numbers must not be compared with the rows above.

The `--simd` lane kernel streams the 60 MB of weights at ~25 GB/s,
about 2.4 ms of a 4.6 ms token on the JVM; the rest is the boxed
attention, RoPE and KV-cache loops around the GEMVs. `--gpu --simd` moves the GEMVs whose matrix is big enough and
STAYS on the device -- the three feed-forward matrices per layer and the
classifier head, two thirds of the multiply-adds; the 288x288 projections are a
tie at ~12 us and stay on the CPU -- from their second token on, once the
library has seen the weight twice unwritten ([the guide](../../doc/en/guides/gpu-acceleration.md)).
That is about 1.3x with the story unchanged, and the next 2x on this program is
the glue, not a GEMV. On the interpreter the same flag buys nothing: the tree
walk around the GEMVs dominates there. On an Apple M4 Max the JVM decodes the
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
