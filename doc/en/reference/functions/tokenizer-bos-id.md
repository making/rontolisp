# tokenizer:bos-id

`(tokenizer:bos-id tk)`

The tokenizer's beginning-of-sequence id, or `nil` when it has none. It is what `(tokenizer:encode tk text :bos t)` puts in front of the ids; a base model that wants no BOS simply leaves the keyword off.

```lisp
(defparameter *tk*
  (tokenizer:make-bpe
   #("<|endoftext|>" "h" "e" "l" "o" "Ġ" "w" "r" "d"
     "he" "hel" "hell" "hello" "Ġw" "Ġwo" "Ġwor" "Ġworl" "Ġworld")
   '("h e" "he l" "hel l" "hell o" "Ġ w" "Ġw o" "Ġwo r" "Ġwor l" "Ġworl d")
   :specials '("<|endoftext|>") :bos 0 :eos 0))
(tokenizer:bos-id *tk*)
; => 0
```
