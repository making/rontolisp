# tokenizer:token-string

`(tokenizer:token-string tk id)`

語彙が `id` に割り当てているトークン文字列を返します。バイトレベル BPE ではこれは**バイトレベル表記**であり、テキストではなくマージリストが書かれている表記です。スペースは `Ġ`、改行は `Ċ` で、すべてのバイトが印字可能で空白でない文字になります。id をテキストに戻すには [`tokenizer:decode`](tokenizer-decode.md) を使ってください。

```lisp
(defparameter *tk*
  (tokenizer:make-bpe
   #("<|endoftext|>" "h" "e" "l" "o" "Ġ" "w" "r" "d"
     "he" "hel" "hell" "hello" "Ġw" "Ġwo" "Ġwor" "Ġworl" "Ġworld")
   '("h e" "he l" "hel l" "hell o" "Ġ w" "Ġw o" "Ġwo r" "Ġwor l" "Ġworl d")
   :specials '("<|endoftext|>") :bos 0 :eos 0))
(tokenizer:token-string *tk* 17)
; => "Ġworld"
```
