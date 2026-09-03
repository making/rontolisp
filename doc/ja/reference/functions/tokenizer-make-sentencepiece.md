# tokenizer:make-sentencepiece

`(tokenizer:make-sentencepiece pieces scores &key specials bos eos)`

SentencePiece 形式の BPE トークナイザを構築します。Llama 2 と TinyLlama のもので、`examples/llama2/llama2.lisp` が動かしているのと同じ方式です。`pieces` は id 順のピース文字列の列、`scores` は対応するマージスコアです。エンコードはダミーの先頭スペースを付けたうえで、連結結果のスコアが**最も高い**隣接ペアを繰り返しマージします。U+2581 はスペースに読み替えるので、GGUF の `tokenizer.ggml.tokens` も `llama2.c` の `tokenizer.bin` が変換済みで持つピースもそのまま渡せます。バイトフォールバックのピース `<0x00>`..`<0xFF>` は位置ではなく名前で引きます。

```lisp
(tokenizer:encode
 (tokenizer:make-sentencepiece #("<unk>" "<s>" "</s>" " " "a" "b" "ab" " a")
                               #(0.0 0.0 0.0 -1.0 -2.0 -3.0 -0.5 -0.4))
 "ab")
; => (7 5)
```
