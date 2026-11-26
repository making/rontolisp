# tokenizer:decode

`(tokenizer:decode tk ids)`

トークン id 列が表すテキストを返します。受け取るのは**列全体**で、そのため [`tokenizer:encode`](tokenizer-encode.md) と往復します。マルチバイト文字は日常的に 2 つのトークンにまたがりますし、SentencePiece のエンコードが付けるダミーの先頭スペースもここで取り除きます。1 トークンずつデコードする生成ループには [`tokenizer:decode-bytes`](tokenizer-decode-bytes.md) を使ってください。

```lisp
(defparameter *tk*
  (tokenizer:make-bpe
   #("<|endoftext|>" "h" "e" "l" "o" "Ġ" "w" "r" "d"
     "he" "hel" "hell" "hello" "Ġw" "Ġwo" "Ġwor" "Ġworl" "Ġworld")
   '("h e" "he l" "hel l" "hell o" "Ġ w" "Ġw o" "Ġwo r" "Ġwor l" "Ġworl d")
   :specials '("<|endoftext|>") :bos 0 :eos 0))
(tokenizer:decode *tk* '(12 17))
; => "hello world"
```
