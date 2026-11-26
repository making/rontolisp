# tokenizer:encode

`(tokenizer:encode tk text &key bos eos)`

Encodes `text` and returns the list of token ids. A special token is matched whole, before pre-tokenization; everything between two of them goes through the tokenizer's model. `:bos` and `:eos` add the tokenizer's beginning- and end-of-sequence ids around the result. Unicode NORMALIZATION is deliberately not applied -- Qwen's `tokenizer.json` declares an NFC normalizer, `llama.cpp` does not implement it either, and NFC is the identity on text that is already composed; compose the text first if it can carry decomposed combining marks.

```lisp
(defparameter *tk*
  (tokenizer:make-bpe
   #("<|endoftext|>" "h" "e" "l" "o" "Ġ" "w" "r" "d"
     "he" "hel" "hell" "hello" "Ġw" "Ġwo" "Ġwor" "Ġworl" "Ġworld")
   '("h e" "he l" "hel l" "hell o" "Ġ w" "Ġw o" "Ġwo r" "Ġwor l" "Ġworl d")
   :specials '("<|endoftext|>") :bos 0 :eos 0))
(tokenizer:encode *tk* "hello world")
; => (12 17)
```
