# gguf パッケージの関数

`gguf` パッケージは **GGUF チェックポイント**を読みます。ダウンロードした小規模
言語モデルがたいていそのまま入っている単一ファイルで、ハイパーパラメータ・
トークナイザ・重みを、公開者が選んだ幅のまま一緒に持っています。rontolisp 自身で
書かれており、`linalg` や `geom` と同じく最初の使用時に読み込まれます。

**Common Lisp の一部ではありません**。名前は `gguf:` 修飾子で参照します。標準
パッケージと通常のファイル I/O 以外には何も依存しないので、ファイルシステムのある
すべてのバックエンドで、同じ答えを返して動きます。

読む前に知っておくとよいことが 2 つあります。

- **トークナイザとハイパーパラメータはタダで読めます。**どちらもファイル先頭の
  キー/値ブロックにあるので、`(gguf:read path :metadata-only t)` は重みの手前で
  止まり、ギガバイト側には一切触れません。
- **量子化テンソルは「その本体を要求されたとき」だけ拒否されます。**F32 / F16 /
  BF16 は読み込め、Q4_K_M や Q8_0 のファイルでも、開くこと・ディレクトリ全体を
  見ること・語彙を取り出すことはできます。

| 関数 | 例 | 結果 |
|------|----|------|
| `gguf:read` | `(gguf:read "model.gguf" :metadata-only t)` | チェックポイントを読む。`:metadata-only` は重みの手前で止まり、`:only` は読むテンソルを指定 |
| `gguf:version` | `(gguf:version f)` | ファイルが宣言する GGUF バージョン（現行はすべて 3） |
| `gguf:metadata` | `(gguf:metadata f)` | キー/値ブロック全体をハッシュテーブルで。配列は単純ベクタ |
| `gguf:metadata-value` | `(gguf:metadata-value f "llama.block_count")` | 1 つのキーの値。既定値が返るのは本当にキーが無いときだけ |
| `gguf:tensor-names` | `(gguf:tensor-names f)` | テンソル名をディレクトリ順で |
| `gguf:tensor-info` | `(gguf:tensor-info f "token_embd.weight")` | 1 つのテンソルのディレクトリエントリ。dims は**行優先**、型・要素数・バイト数・オフセット |
| `gguf:tensor` | `(gguf:tensor f "output_norm.weight")` | テンソルとして読み込まれたパック済み浮動小数点配列。未読み込みなら `nil` |
| `gguf:tokenizer-fields` | `(gguf:tokenizer-fields f)` | トークナイザ関連フィールドを plist で。`tokenizer:make-bpe` が受け取る形 |
