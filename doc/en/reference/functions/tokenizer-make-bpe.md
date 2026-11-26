# tokenizer:make-bpe

`(tokenizer:make-bpe tokens merges &key kind specials ignore-merges bos eos)`

Builds a GPT-2-style **byte-level** BPE tokenizer -- what SmolLM2, Qwen 2.5 / 3 / 3.5, Llama 3 and every other current small model uses. `tokens` is a sequence of token strings indexed by id, in the byte-level spelling the merge list uses (a GGUF's `tokenizer.ggml.tokens`, or a `tokenizer.json`'s `vocab` with its `added_tokens` filled in); `merges` is the ranked merge list, best rank first, each entry either the string `"left right"` or a two-element sequence. `:kind` names the pre-tokenizer (see [`tokenizer:pre-tokenize`](tokenizer-pre-tokenize.md)), `:specials` lists the token strings matched whole before pre-tokenization, `:ignore-merges` is Llama 3's flag (a pre-token that is itself in the vocabulary is that id), and `:bos` / `:eos` are the ids [`tokenizer:encode`](tokenizer-encode.md) adds when asked. The library never opens a file: the vocabulary is always an argument.

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
