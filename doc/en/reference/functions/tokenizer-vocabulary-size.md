# tokenizer:vocabulary-size

`(tokenizer:vocabulary-size tk)`

The number of ids the vocabulary defines -- the length of the `tokens` sequence [`tokenizer:make-bpe`](tokenizer-make-bpe.md) was given, special tokens included. This is the number a model's classifier head has to agree with.

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
