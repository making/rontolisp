# 674. A byte-level BPE tokenizer (SmolLM2, Qwen, Llama 3)

Difficulty: Medium

Part of `.todo/670`. Depends on `.todo/673` or `.todo/675` for its vocabulary; needed by
`.todo/489`'s SmolLM2 rungs and by any Qwen or Llama 3 model.

`llama2.lisp` carries one tokenizer: the SentencePiece-style BPE of Llama 2 (pieces with
scores, greedy merges by score, byte-fallback tokens `<0x00>`..`<0xFF>`). TinyLlama uses
it. Every other small model -- SmolLM2, Qwen2.5, Llama 3.2, Phi, Gemma's is SentencePiece
again -- uses **GPT-2-style byte-level BPE**: a pre-tokenizer regex splits the text into
words, each word's UTF-8 bytes are mapped through GPT-2's byte-to-unicode table, and a
ranked `merges` list is applied greedily by rank. The vocabulary and the merges are in
`tokenizer.json` (safetensors models) and in `tokenizer.ggml.tokens` / `merges` with
`tokenizer.ggml.model = "gpt2"` (GGUF).

## Do

1. The encoder: byte-to-unicode table, merge ranks in a hash table of pairs, the greedy
   merge loop, then token lookup; the decoder is the reverse table and a UTF-8 decode.
   Plain Lisp, portable to every backend, no regex library: the pre-tokenizer patterns
   are four fixed shapes and are **hand-coded** as scanners keyed by
   `tokenizer.ggml.pre` / the `tokenizer.json` `pre_tokenizer` -- that is what llama.cpp
   does, and it is what keeps this out of a Unicode-regex engine. Read from the
   `tokenizer.json` files 2026-09-03:
   - GPT-2 (SmolLM2): contractions | ` ?\p{L}+` | ` ?\p{N}+` | ` ?[^\s\p{L}\p{N}]+` | whitespace.
   - Llama 3 = **LFM2.5** (`\p{N}{1,3}`, `[^\r\n\p{L}\p{N}]?\p{L}+`, the `\s*[\r\n]+`
     and `\s+(?!\S)` whitespace rules); vocab 128256 / 65536.
   - Qwen2 = **Qwen3** (`\p{N}` single digits, otherwise Llama 3's shape); vocab 151936.
   - **Qwen3.5** (= 3.6, 3.8): Qwen2's with `[\p{L}\p{M}]+` in place of `\p{L}+` --
     combining marks stay with their letter; vocab 248320.
   Each needs a Unicode letter / number / mark class test per code point; the JVM has
   `Character.getType`, wasm needs a table -- one small shared table of the three
   classes, not a regex engine.
2. Special tokens (`<|im_start|>`, `<|begin_of_text|>`, `<|endoftext|>`): matched as
   whole strings before pre-tokenization, from `added_tokens` / `token_type`.
3. Chat templates are NOT this item: a base model takes raw text; an instruct model's
   template is a string the example can spell out for the one model it demos.
4. Where: `tokenizers.lisp` beside `gguf.lisp` (or inside it -- decide by what the
   safetensors reader needs), with `llama2.lisp`'s SentencePiece encoder moved in next to
   it so one `tokenizer:encode` / `decode` dispatches on the model kind.

## Verify

- Against `tokenizers` (the Python library) on a fixed corpus -- ASCII, spaces and
  newlines, numbers, CJK, emoji, a special token -- for SmolLM2-135M and Qwen2.5-0.5B:
  identical ids. The expected ids are generated once and checked in with the corpus;
  the test needs no Python.
- Round trip: `decode(encode(s)) == s` for the same corpus.
- `ci-spec.yaml`: the corpus through the checked-in SmolLM2 vocabulary (the vocabulary
  is 49152 tokens, ~1 MB as text -- acceptable as a fixture, or trimmed to the merges the
  corpus reaches). The 248320-token Qwen3.5 vocabulary is NOT a fixture; its scanner is
  pinned on a trimmed vocabulary and the full one is the manual model run.
