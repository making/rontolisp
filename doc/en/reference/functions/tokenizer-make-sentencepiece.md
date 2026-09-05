# tokenizer:make-sentencepiece

`(tokenizer:make-sentencepiece pieces scores &key specials bos eos)`

Builds a SentencePiece-style BPE tokenizer -- Llama 2 and TinyLlama's, the one `examples/llm/llm.lisp` runs. `pieces` is a sequence of piece strings indexed by id and `scores` the matching merge scores; the encode adds a dummy prefix space, then repeatedly merges the adjacent pair whose concatenation scores HIGHEST. U+2581 is translated to a space, so a GGUF's `tokenizer.ggml.tokens` and a `llama2.c` `tokenizer.bin`'s already-translated pieces are both accepted as they come, and the byte-fallback pieces `<0x00>`..`<0xFF>` are looked up by name rather than by position.

```lisp
(tokenizer:encode
 (tokenizer:make-sentencepiece #("<unk>" "<s>" "</s>" " " "a" "b" "ab" " a")
                               #(0.0 0.0 0.0 -1.0 -2.0 -3.0 -0.5 -0.4))
 "ab")
; => (7 5)
```
