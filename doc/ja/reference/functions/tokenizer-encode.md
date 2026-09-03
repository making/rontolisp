# tokenizer:encode

`(tokenizer:encode tk text &key bos eos)`

`text` をエンコードし、トークン id のリストを返します。特殊トークンは事前トークナイズより前に丸ごと一致させ、その間のテキストだけがトークナイザのモデルを通ります。`:bos` と `:eos` は結果の前後にトークナイザの開始 / 終了 id を付けます。Unicode の**正規化**は意図的に行いません。Qwen の `tokenizer.json` は NFC 正規化を宣言していますが `llama.cpp` も実装しておらず、すでに合成済みのテキストに対して NFC は恒等変換だからです。分解済みの結合文字を含みうる場合は呼び出し側で合成してください。

```lisp
(defparameter *tk*
  (tokenizer:make-bpe
   #("<|endoftext|>" "h" "e" "l" "o" "Ġ" "w" "r" "d"
     "he" "hel" "hell" "hello" "Ġw" "Ġwo" "Ġwor" "Ġworl" "Ġworld")
   '("h e" "he l" "hel l" "hell o" "Ġ w" "Ġw o" "Ġwo r" "Ġwor l" "Ġworl d")
   :specials '("<|endoftext|>") :bos 0 :eos 0))
(tokenizer:encode *tk* "hello world")
; => (12 17)
```
