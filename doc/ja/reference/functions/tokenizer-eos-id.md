# tokenizer:eos-id

`(tokenizer:eos-id tk)`

トークナイザの終了（EOS）id を返します。持たない場合は `nil` です。`(tokenizer:encode tk text :eos t)` が末尾に付ける値であり、生成ループが停止条件に使う id です。

```lisp
(defparameter *tk*
  (tokenizer:make-bpe
   #("<|endoftext|>" "h" "e" "l" "o" "Ġ" "w" "r" "d"
     "he" "hel" "hell" "hello" "Ġw" "Ġwo" "Ġwor" "Ġworl" "Ġworld")
   '("h e" "he l" "hel l" "hell o" "Ġ w" "Ġw o" "Ġwo r" "Ġwor l" "Ġworl d")
   :specials '("<|endoftext|>") :bos 0 :eos 0))
(tokenizer:eos-id *tk*)
; => 0
```
