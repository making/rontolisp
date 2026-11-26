# tokenizer Package Functions

The `tokenizer` package is the BPE a published language model ships with: the
GPT-2-style **byte-level** BPE that SmolLM2, Qwen 2.5 / 3 / 3.5, Llama 3 and
LFM2.5 use, and the SentencePiece-style BPE with per-piece scores that Llama 2
and TinyLlama use, behind one `tokenizer:encode` / `tokenizer:decode`. It is
written in rontolisp itself and loaded on first use like `linalg` and `geom`.

It is **not part of Common Lisp**; reference its names with the `tokenizer:`
qualifier. It reaches for nothing but the standard package -- **the vocabulary
is always an argument, never a file this package opens** -- so it runs on every
backend and in the browser playground, and it is usable with a vocabulary from
anywhere: a GGUF's `tokenizer.ggml.tokens` / `merges` fields, a
`tokenizer.json`'s `vocab` / `merges`, or a fixture written out by hand.

| Function | Example | Result |
|----------|---------|--------|
| `tokenizer:make-bpe` | `(tokenizer:make-bpe tokens merges :kind :smollm)` | a byte-level BPE tokenizer over the given vocabulary and ranked merge list |
| `tokenizer:make-sentencepiece` | `(tokenizer:make-sentencepiece pieces scores)` | a SentencePiece-style tokenizer: pieces with scores, greedy merges by score |
| `tokenizer:encode` | `(tokenizer:encode tk "hello world" :bos t)` | the token ids, special tokens matched whole before pre-tokenization |
| `tokenizer:decode` | `(tokenizer:decode tk ids)` | the text a WHOLE id sequence stands for; round-trips `tokenizer:encode` |
| `tokenizer:decode-bytes` | `(tokenizer:decode-bytes tk ids)` | the raw bytes, for a generation loop decoding one token at a time |
| `tokenizer:pre-tokenize` | `(tokenizer:pre-tokenize :qwen2 "a 12")` | the text cut into pre-tokens by one of the five shapes |
| `tokenizer:token-string` | `(tokenizer:token-string tk 17)` | the byte-level spelling the vocabulary gives an id |
| `tokenizer:token-id` | `(tokenizer:token-id tk "hello")` | the id of a token string, or `nil` |
| `tokenizer:vocabulary-size` | `(tokenizer:vocabulary-size tk)` | how many ids the vocabulary defines |
| `tokenizer:bos-id` | `(tokenizer:bos-id tk)` | the beginning-of-sequence id `:bos t` adds, or `nil` |
| `tokenizer:eos-id` | `(tokenizer:eos-id tk)` | the end-of-sequence id `:eos t` adds, or `nil` |
