# tokenizer:decode

`(tokenizer:decode tk ids)`

The text the token ids stand for. It takes a WHOLE sequence, so that it round-trips [`tokenizer:encode`](tokenizer-encode.md): a multi-byte character straddles two tokens routinely, and a SentencePiece encode opens with a dummy prefix space that this takes back off. A generation loop that decodes one token at a time wants [`tokenizer:decode-bytes`](tokenizer-decode-bytes.md) instead.

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
