# safetensors:read

`(safetensors:read path &key only element-type)`

safetensors チェックポイントを、テンソル名 -> ファイルの形のパックされた浮動小数点配列（1 次元のテンソルはランク 1 のベクタ、それ以外はランク N のパック配列）のハッシュテーブル（`equal`、文字列キー）に読み込みます。`path` は `.safetensors` ファイル、`model.safetensors.index.json`（各シャードは 1 回だけ開き 1 回だけ歩きます）、またはそのどちらかを含むディレクトリです。`element-type` は `single-float`（既定）か `double-float`。F32 テンソルはそのまま、F16 と BF16 は [`checkpoint`](checkpoint.md) のステージングを通して読み、それ以外の dtype はテンソル名と dtype を挙げたエラーになります。`only` は名前に対する述語で、拒否されたテンソルはステージングされず有界の読み取りで飛ばされます。マルチモーダルなチェックポイントのタワーや投機的ヘッドをディスクに残すのはこの方法です。

```console
CL-USER> (defparameter *w* (safetensors:read "TinyLlama-1.1B-Chat-v1.0"
                                             :only (lambda (name) (search "layers.0." name))))
*W*
CL-USER> (hash-table-count *w*)
9
CL-USER> (array-dimensions (gethash "model.layers.0.self_attn.q_proj.weight" *w*))
(2048 2048)
CL-USER> (array-element-type (gethash "model.layers.0.input_layernorm.weight" *w*))
SINGLE-FLOAT
```

## バックエンドのサポート

ファイルシステムのあるすべてのバックエンド。現在はインタプリタとコンパイルされた `.class`/`.jar`。WASM バックエンドは `rontolisp:widen-float-bits` が揃い次第です（F32 テンソルは拡張が不要なのでどこでも読めます）。
