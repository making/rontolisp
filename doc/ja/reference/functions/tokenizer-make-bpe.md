# tokenizer:make-bpe

`(tokenizer:make-bpe tokens merges &key kind specials ignore-merges bos eos)`

GPT-2 形式の**バイトレベル** BPE トークナイザを構築します。SmolLM2、Qwen 2.5 / 3 / 3.5、Llama 3 をはじめ、現在の小規模モデルはほぼこれを使います。`tokens` は id 順に並んだトークン文字列の列で、マージリストが使うバイトレベル表記で与えます（GGUF の `tokenizer.ggml.tokens`、または `tokenizer.json` の `vocab` に `added_tokens` を埋めたもの）。`merges` はランク順（最良が先頭）のマージリストで、各要素は `"left right"` という文字列か2 要素の列です。`:kind` は事前トークナイザの種類（[`tokenizer:pre-tokenize`](tokenizer-pre-tokenize.md) を参照）、`:specials` は事前トークナイズより前に丸ごと一致させる特殊トークン、`:ignore-merges` は Llama 3 のフラグ（語全体が語彙にあればその id をそのまま使う）、`:bos` / `:eos` は [`tokenizer:encode`](tokenizer-encode.md) が要求されたときに付ける id です。このライブラリはファイルを開きません。語彙は常に引数です。

```lisp
(defparameter *tk*
  (tokenizer:make-bpe
   #("<|endoftext|>" "h" "e" "l" "o" "Ġ" "w" "r" "d"
     "he" "hel" "hell" "hello" "Ġw" "Ġwo" "Ġwor" "Ġworl" "Ġworld")
   '("h e" "he l" "hel l" "hell o" "Ġ w" "Ġw o" "Ġwo r" "Ġwor l" "Ġworl d")
   :specials '("<|endoftext|>") :bos 0 :eos 0))
(tokenizer:vocabulary-size *tk*)
; => 18
```
