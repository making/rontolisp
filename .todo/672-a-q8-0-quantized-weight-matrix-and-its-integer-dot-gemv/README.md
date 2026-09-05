# The Q8_0 quantized matrix: the oracle chain and the both-JIT harness, 2026-09-05

The record for `.todo/672` (closed 2026-09-05; the mechanics live in
`.kb/quantized-matrix.md`). Two things are here that the `.kb` file only summarizes: how
the item's central check -- "the number a Q8_0 model produces is the number `llama.cpp`
produces from the same file" -- was made a legitimate check before it was run, and the
kernel numbers under both JITs.

## The oracle chain, in the order it was established

**1. Prompt equality by construction, no template.** Every comparison below is a RAW
completion of `"Once upon a time"` at temperature 0. The Qwen3.5 family tokenizes it
without a BOS token, and both sides were shown to feed exactly the same four ids before
any output was read:

| side | ids |
| --- | --- |
| `llama-tokenize` / `llama-completion --verbose-prompt` | `12162 5028 264 854` |
| `gguf:tokenizer-fields` -> `tokenizer:make-bpe` -> `tokenizer:encode` | `(12162 5028 264 854)` |

Raw rather than chat, and not for convenience: our chat-template rendering is the one
component that had, by the time this was written, twice been shown to differ from the
model's own `tokenizer.chat_template` on the Qwen family (Qwen3.5-0.8B here, Qwen3-0.6B
on the other box -- both identical to `llama.cpp` in raw mode and divergent in chat
mode, while LFM2.5-1.2B is identical in both). A chat-mode comparison would measure that
defect; a raw one removes it, so what is left to differ is arithmetic. `.todo/677`'s
"same character, different sentence" was the chat harness, not the kernels. Do not
"improve" this check by making it a realistic chat prompt.

**2. The baseline was established, not assumed.** Before the Q8_0 file was compared,
the BF16 file was: `examples/llama2/llama2.lisp --simd --parallel` (f32 GEMVs over the
bf16 weights widened at load) against `llama.cpp 0eadefebd` (2026-09-01, CPU/NEON build,
`-t 8`, `--temp 0 --repeat-penalty 1.0 --top-k 0 --top-p 1.0 --min-p 0 -no-cnv`), both
over `ggml-org/Qwen3.5-0.8B-GGUF`'s `Qwen3.5-0.8B-BF16.gguf` (sha256 `9a7bed40...`,
`.todo/672`'s table). **Token-identical over the 64 generated tokens compared**;
`llama.cpp`'s 64 ids are an exact prefix of our 65 (the extra one is run.c's `-n`
counting convention in `llama2.lisp`, not a divergence), and `llama.cpp -n 66` continues
with the same next word. The 64 generated ids:

```
11 303 264 1814 1332 4156 557 1801 314 31137 11 1017 557 264 3175 2993 314 18546 2512 264
328 9241 21183 3158 271 2523 1366 11 303 1004 4472 1814 11 31137 513 1801 685 314 453 33268
11 24972 24795 11 321 54683 13 1921 303 411 2184 21183 1814 11 2414 2098 6821 6716 13 561
453 33268 321 24972
```

("`, in a world where everything was made of atoms, there was a special kind of atom
called a "superatom".\n\nYou know, in our normal world, atoms are made up of protons,
neutrons, and electrons. But in this superatom world, something really interesting
happened. The protons and neut`".) This is what makes `llama.cpp` a legitimate baseline
for the Q8_0 check at all: a reader who finds only the Q8_0 comparison cannot tell a
quantized-path difference from a standing difference between us and ggml, and that
inference is available only because this run came first. Together with the other box's
LFM2.5 result, our f32 GEMV reproduces ggml's bf16 kernels to the last token on two
architectures.

Provenance: taken twice, with a `develop` jar built from `f36bfbd` (2026-09-03) and
again with the build this item closed at, and the two runs are identical to the last
token -- which is also a data point for the other box's question of whether Qwen3.5's
f32 decode drifted across the `2275c000..1cb95b03` boundary: not between these two, on
this prompt.

**3. The Q8_0 file, same method, same build.** `Qwen3.5-0.8B-Q8_0.gguf` (sha256
`37ae482d...`), loaded by `gguf:read` with every weight matrix staying a quantized matrix
(0.83 GB of blocks read straight into place; `token_embd.weight` included, which
`linalg:row` reads a row of into `#f`), GEMVs on the integer-dot kernel under `--simd
--parallel`:

| | generated ids |
| --- | --- |
| `llama.cpp`, Q8_0 | identical to its own BF16 output above, all 64 |
| ours, Q8_0 | identical to the above for **60** tokens, then `54683 303 279 2184 21183` |

i.e. "...something really interesting happened. **The protons and neut**rons" against
"...happened. **The electrons in the superatom**". An argmax flip at token 61 -- the
model is running, the first 60 choices agree with three other decodes, and the flip is
where two Q8_0 implementations are allowed to part: `llama.cpp` quantizes the activation
in f32 (`quantize_row_q8_0`, ties to even) and folds its dot products in f32 in its own
order; ours quantizes in double with CL's `round` and folds one double step per block --
because ours is pinned to the scalar `vec.lisp` defun bit for bit, not to ggml. The
comparison is therefore a check that nothing is WRONG (a wrong block layout, scale
position or sign would not survive 60 tokens), not a bit-identity claim, and with the
template excluded by construction the flip is in the quantized path and nowhere else.
What IS bit-identical to ggml is the quantizer: `rontolisp:quantize` writes
`quantize_row_q8_0_ref`'s bytes (`QuantizedMatrixTest.quantizeProducesGgmlsBytes`).

Trap met on the way, filed as `.todo/700`: `java -jar` without `--add-modules
jdk.incubator.vector` prints one warning and runs the decode on the scalar defuns at
~0.01 tok/s, which reads as a hang.

## The both-JIT harness

`src/test/java/am/ik/rontolisp/eval/Q8GemvBench.java` (the interpreter's kernels) and
`codegen/jvm/Q8TemplateGemvBench.java` (the copy embedded in every `--simd` `.class`,
reached through the real bridge entries over headered arrays), run by `bench.sh` under
Graal and C2. Each shape times the shipped f32 GEMV, the shipped fused bf16 GEMV
(`.todo/488`) and the Q8_0 integer-dot GEMV over the SAME gaussian weights, serial and
`--parallel`, and prints each arm's relative error against an f64 GEMV plus the assertion
that the Q8_0 kernel equals the defun bit for bit under that JIT.

```bash
./mvnw -o test-compile
.todo/672-a-q8-0-quantized-weight-matrix-and-its-integer-dot-gemv/bench.sh both
```

Numbers: below, once the box was cleared for them.
