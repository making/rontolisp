# tokenizer:token-id

`(tokenizer:token-id tk token)`

トークン文字列 `token` の id を返します。語彙にそのトークンがなければ `nil` です。引数は [`tokenizer:token-string`](tokenizer-token-string.md) が返すのと同じバイトレベル表記です。同じ文字列が複数の id に現れる語彙では、参照実装と同じく最小の id を採ります。

```lisp
(defparameter *tk*
  (tokenizer:make-bpe
   #("<|endoftext|>" "h" "e" "l" "o" "Ġ" "w" "r" "d"
     "he" "hel" "hell" "hello" "Ġw" "Ġwo" "Ġwor" "Ġworl" "Ġworld")
   '("h e" "he l" "hel l" "hell o" "Ġ w" "Ġw o" "Ġwo r" "Ġwor l" "Ġworl d")
   :specials '("<|endoftext|>") :bos 0 :eos 0))
(tokenizer:token-id *tk* "hello")
; => 12
```
