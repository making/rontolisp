# safetensors パッケージの関数

`safetensors` パッケージは Hugging Face のモデルページが持つファイル --
`model.safetensors`、またはシャード分割された `model.safetensors.index.json` -- を、
Python も変換ステップもなしに、パックされた浮動小数点配列に読み込みます。
[`checkpoint`](checkpoint.md) ステージングパッケージ、
[`rontolisp:json-parse`](json-parse.md)、バイトストリームのプリミティブの上に rontolisp
自身で書かれ、`geom` と同じように最初の使用時に読み込まれ、ファイルシステムのあるすべ
てのバックエンドで動きます。**Common Lisp の一部ではありません**。名前は
`safetensors:` 修飾子で参照します -- `safetensors:read` はこのパッケージ自身のシンボ
ルであって `cl:read` ではありません。

形式: リトルエンディアンの `u64` ヘッダ長、その長さの JSON --
`{ "<name>": { "dtype": "BF16", "shape": [rows, cols], "data_offsets": [begin,
end] }, ..., "__metadata__": { ... } }` -- に続いて、ヘッダ末尾からのオフセットにテン
ソルのバイト列が行優先で並びます。F32、F16、BF16 のテンソルを読み、それ以外の dtype
はテンソル名を挙げたエラーになります。ファイルは先頭から順に歩き（ストリームは位置を
変えられません）、`:only` 述語が除外したテンソルはステージングせずに読み飛ばすので、
マルチモーダルなチェックポイントの視覚タワーは I/O のコストだけを払います。
`examples/llama2/llama2.lisp` は TinyLlama、Qwen3.5、LFM2.5 のチェックポイントをこれで
読みます。

| 関数 | 例 | 結果 |
|----------|---------|--------|
| `safetensors:read` | `(safetensors:read "TinyLlama-1.1B-Chat-v1.0")` | テンソル名 -> パックされた浮動小数点配列のハッシュテーブル |
| `safetensors:header` | `(safetensors:header "model.safetensors")` | パースされた JSON ヘッダと、データの開始オフセット |
| `safetensors:entries` | `(safetensors:entries header)` | テンソル情報の `(name dtype shape begin end)` リスト、ファイル順 |
