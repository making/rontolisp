# tokenizer:pre-tokenize

`(tokenizer:pre-tokenize kind text)`

Cuts `text` into pre-tokens the way `kind`'s pre-tokenizer does, as a list of strings. `kind` is `:gpt2`, `:smollm` (SmolLM2: every `\p{N}` character split off on its own, then `:gpt2`), `:llama3` (Llama 3, LFM2.5: digits three at a time), `:qwen2` (Qwen 2.5 and 3: one digit at a time) or `:qwen35` (Qwen 3.5-3.8: a combining mark stays with its letter) -- or the equivalent GGUF `tokenizer.ggml.pre` string (`"gpt-2"`, `"llama-bpe"`, `"lfm2"`, ...), which includes the name a family gives a shape it merely shares. Each shape is a hand-coded scanner over `\p{L}` / `\p{N}` / `\p{M}` rather than a regex engine, matched leftmost-first. This is the half of a byte-level BPE that is not data, so it is exported on its own: the ids only follow once the cut is right.

```lisp
(tokenizer:pre-tokenize :qwen2 "Hi there, 2025!")
; => ("Hi" " there" "," " " "2" "0" "2" "5" "!")
```
