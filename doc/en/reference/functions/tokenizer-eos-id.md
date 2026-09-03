# tokenizer:eos-id

`(tokenizer:eos-id tk)`

The tokenizer's end-of-sequence id, or `nil` when it has none. It is what `(tokenizer:encode tk text :eos t)` appends, and the id a generation loop stops on.

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
