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

## Why `--simd`

Decoding is one token at a time, so every matrix in the model multiplies a
vector: the whole forward pass is GEMV (`vec:matvec`), 15 million multiply-adds
per token for stories15M, and `--simd` lowers it to CPU vector instructions.
Measured on one core, 256 tokens of stories15M:

| backend | scalar | `--simd` |
| --- | --- | --- |
| JVM | 23 tok/s | 87 tok/s |
| wasm-GC (`wasmtime`) | 0.4 tok/s | 46 tok/s |
| interpreter | ~15 s per token | 15-25 tok/s |
| `run.c -O2` (one thread) | 65 tok/s | |
| Java Vector API port of run.c ([kishida's gist](https://gist.github.com/kishida/05656bfcbe840f269784f7dbbee5928e)) | 100 tok/s | 187 tok/s |

stories15M is 60 MB of weights streamed once per token, so the ceiling is memory
bandwidth (~11 GB/s here = ~190 tok/s), which the Java port reaches. The rontolisp
JVM run spends ~80% of its time in the same kind of GEMV kernel and the rest in the
boxed attention / RoPE loops; the gap to the Java port is that glue plus the
`--simd` kernel's deliberately pinned 128-bit accumulation (one chain per row, so
results agree bit for bit with the WASM `f32x4` kernels on every host).

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
