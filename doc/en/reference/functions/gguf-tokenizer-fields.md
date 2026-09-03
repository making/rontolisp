# gguf:tokenizer-fields

`(gguf:tokenizer-fields file)`

The file's tokenizer fields as a plist, in the shape [`tokenizer:make-bpe`](tokenizer-make-bpe.md) and [`tokenizer:make-sentencepiece`](tokenizer-make-sentencepiece.md) take: `:model` (`"gpt2"` = byte-level BPE with merges, `"llama"` = SentencePiece-style with scores), `:pre` (the pre-tokenizer name, which `tokenizer:` accepts as it is), `:tokens`, `:scores`, `:merges`, `:token-type`, `:bos` and `:eos`. A field the file does not carry is `nil` -- a `gpt2` vocabulary has merges and no scores, a `llama` one the other way round.

The fields are surfaced unchanged rather than interpreted, because interpreting them is the tokenizer's job. Since `:metadata-only t` already has all of them, taking a checkpoint's tokenizer never reads its weights.

```console
CL-USER> (let ((fields (gguf:tokenizer-fields *m*)))
           (list (getf fields :model) (getf fields :pre)
                 (length (getf fields :tokens)) (length (getf fields :merges))))
("gpt2" "smollm" 49152 48900)
CL-USER> (let* ((fields (gguf:tokenizer-fields *m*))
                (tk (tokenizer:make-bpe (getf fields :tokens) (getf fields :merges)
                                        :kind (getf fields :pre))))
           (tokenizer:encode tk "Once upon a time"))
(6403 1980 253 655)
```
