# tokenizer:token-id

`(tokenizer:token-id tk token)`

The id of the token string `token`, or `nil` when the vocabulary has no such token. The argument is the byte-level spelling, the same one [`tokenizer:token-string`](tokenizer-token-string.md) hands back; a vocabulary that repeats a string keeps the lowest id, as the reference implementations do.

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
