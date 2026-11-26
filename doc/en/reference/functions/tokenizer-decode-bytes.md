# tokenizer:decode-bytes

`(tokenizer:decode-bytes tk ids)`

The raw bytes the token ids stand for, as a fill-pointer byte vector. This is the streaming half of [`tokenizer:decode`](tokenizer-decode.md): a generation loop accumulates the bytes of the tokens it has produced and decodes what is complete, which is the only way a character split across two tokens ever prints correctly. Nothing is stripped or rewritten.

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
