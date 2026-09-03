# tokenizer:bos-id

`(tokenizer:bos-id tk)`

トークナイザの開始（BOS）id を返します。持たない場合は `nil` です。`(tokenizer:encode tk text :bos t)` が id 列の先頭に付けるのがこの値です。BOS を必要としないベースモデルではキーワードを付けなければよいだけです。

```lisp
(defparameter *tk*
  (tokenizer:make-bpe
   #("<|endoftext|>" "h" "e" "l" "o" "Ġ" "w" "r" "d"
     "he" "hel" "hell" "hello" "Ġw" "Ġwo" "Ġwor" "Ġworl" "Ġworld")
   '("h e" "he l" "hel l" "hell o" "Ġ w" "Ġw o" "Ġwo r" "Ġwor l" "Ġworl d")
   :specials '("<|endoftext|>") :bos 0 :eos 0))
(tokenizer:bos-id *tk*)
; => 0
```
