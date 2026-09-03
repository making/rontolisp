# gguf:tokenizer-fields

`(gguf:tokenizer-fields file)`

ファイルのトークナイザ関連フィールドを plist で返します。[`tokenizer:make-bpe`](tokenizer-make-bpe.md) と [`tokenizer:make-sentencepiece`](tokenizer-make-sentencepiece.md) が受け取る形です。`:model`（`"gpt2"` はマージリスト付きのバイトレベル BPE、`"llama"` はスコア付きの SentencePiece 形式）、`:pre`（事前トークナイザ名。`tokenizer:` はこの文字列をそのまま受け取ります）、`:tokens`、`:scores`、`:merges`、`:token-type`、`:bos`、`:eos` です。ファイルが持たないフィールドは `nil` になります（`gpt2` の語彙は merges を持ち scores を持たず、`llama` はその逆です）。

解釈はトークナイザ側の仕事なので、ここでは値をそのまま出します。`:metadata-only t` の時点で全部揃っているため、チェックポイントからトークナイザを取り出すのに重みを読む必要はありません。

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
