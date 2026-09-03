# tokenizer:pre-tokenize

`(tokenizer:pre-tokenize kind text)`

`kind` の事前トークナイザと同じ切り方で `text` を分割し、文字列のリストとして返します。`kind` は `:gpt2`、`:smollm`（SmolLM2。`\p{N}` の文字を 1 文字ずつ切り出してから `:gpt2`）、`:llama3`（Llama 3、LFM2.5。数字は 3 桁ずつ）、`:qwen2`（Qwen 2.5 と 3。数字は 1 桁ずつ）、`:qwen35`（Qwen 3.5〜3.8。結合文字は直前の文字に付く）のいずれか、または GGUF の `tokenizer.ggml.pre` の文字列（`"gpt-2"`、`"llama-bpe"` など）です。どの形も正規表現エンジンではなく `\p{L}` / `\p{N}` / `\p{M}` を直接見る手書きのスキャナで、最左優先で一致させます。バイトレベル BPE のうちデータでない側なので単独で公開しています。切り方が正しくなって初めてid が合います。

```lisp
(tokenizer:pre-tokenize :qwen2 "Hi there, 2025!")
; => ("Hi" " there" "," " " "2" "0" "2" "5" "!")
```
