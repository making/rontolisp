# gguf:read

`(gguf:read path &key only metadata-only element-type)`

`path` にある GGUF チェックポイント（ダウンロードした小規模言語モデルがたいていそのまま入っている単一ファイルで、ハイパーパラメータ・トークナイザ・重みを一緒に持っています）を読み、他の `gguf:` 関数が受け取る値を返します。

`:metadata-only t` はテンソルディレクトリまでで読むのをやめます。ハイパーパラメータとトークナイザ一式はそこまでに入っているので、ギガバイト側には一切触れません。チェックポイントの中身を見るとき、また [`tokenizer:make-bpe`](tokenizer-make-bpe.md) に語彙を渡すときはこれを使います。`:only` は読み込むテンソル名のリストで、残りは読み飛ばします。ただし rontolisp のストリームはシークできないため、節約できるのはメモリと変換であって I/O ではありません（読み飛ばす分も読み進めます）。`:element-type` は各テンソルが載るパック済み浮動小数点配列の幅で、`'single-float`（既定）、`'double-float`、またはインタプリタと JVM では `'bfloat16` です（他のバックエンドはこの幅を名前を挙げて拒否します）。BF16 テンソルを `'bfloat16` の宛先に読む場合はファイルのバイト列そのままを 1 回で転送し、拡張は一切ありません。F32 と F16 のテンソルは流しながらこの幅に丸められます。

**F32 / F16 / BF16 のテンソルはパックされた浮動小数点配列に、Q8_0 のテンソルは量子化行列として読み込めます**（[`rontolisp:quantize`](rontolisp-quantize.md)。ファイルのバイト列そのままを 1 回の転送で読み、`:element-type` は適用されません。インタプリタと JVM のみで、WASM バックエンドはそのテンソルでエラーを通知します）。**それ以外の量子化テンソルは「その本体を要求されたとき」だけ名前を挙げて拒否します。**そのため Q4_K_M のチェックポイントでも、ファイルは開けますし、ディレクトリ全体もトークナイザも取り出せます。

```console
CL-USER> (defparameter *m* (gguf:read "SmolLM2-135M-Instruct-f16.gguf" :metadata-only t))
*M*
CL-USER> (list (gguf:metadata-value *m* "general.architecture")
               (gguf:metadata-value *m* "llama.block_count")
               (length (gguf:tensor-names *m*)))
("llama" 30 272)
CL-USER> (gguf:tensor (gguf:read "SmolLM2-135M-Instruct-f16.gguf"
                                 :only '("output_norm.weight"))
                      "output_norm.weight")
#f(1.7578125 1.8203125 1.78125 ...)
```
