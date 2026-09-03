# tokenizer:token-string

`(tokenizer:token-string tk id)`

The token string the vocabulary gives `id`. For a byte-level BPE this is the byte-level spelling -- what the merge list is written in, not text: a space is `Ġ` and a newline `Ċ`, so that every byte is a printable, non-whitespace character. Use [`tokenizer:decode`](tokenizer-decode.md) to turn ids back into text.

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
