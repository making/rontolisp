# tokenizer:vocabulary-size

`(tokenizer:vocabulary-size tk)`

語彙が定義している id の個数、すなわち [`tokenizer:make-bpe`](tokenizer-make-bpe.md) に渡した `tokens` 列の長さ（特殊トークンを含む）です。モデルの分類ヘッドの出力次元と一致していなければならない数字です。

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
