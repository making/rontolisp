# tokenizer:decode-bytes

`(tokenizer:decode-bytes tk ids)`

トークン id 列が表す生バイト列を、フィルポインタ付きのバイトベクタとして返します。[`tokenizer:decode`](tokenizer-decode.md) のストリーミング側です。生成ループはこれまでに生成したトークンのバイトを溜め、完成した分だけをデコードします。2 つのトークンに分かれた文字が正しく表示される方法はこれだけです。除去も書き換えも一切行いません。

```lisp
(defparameter *tk*
  (tokenizer:make-bpe
   #("<|endoftext|>" "h" "e" "l" "o" "Ġ" "w" "r" "d"
     "he" "hel" "hell" "hello" "Ġw" "Ġwo" "Ġwor" "Ġworl" "Ġworld")
   '("h e" "he l" "hel l" "hell o" "Ġ w" "Ġw o" "Ġwo r" "Ġwor l" "Ġworl d")
   :specials '("<|endoftext|>") :bos 0 :eos 0))
(tokenizer:decode-bytes *tk* '(17))
; => #(32 119 111 114 108 100)
```
