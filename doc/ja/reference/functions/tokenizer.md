# tokenizer パッケージの関数

`tokenizer` パッケージは、公開されている言語モデルが同梱している BPE トークナイザ
です。SmolLM2、Qwen 2.5 / 3 / 3.5、Llama 3、LFM2.5 が使う GPT-2 形式の
**バイトレベル** BPE と、Llama 2 と TinyLlama が使うピースごとのスコア付き
SentencePiece 形式の BPE を、ひとつの `tokenizer:encode` / `tokenizer:decode` の
背後に置いています。rontolisp 自身で書かれており、`linalg` や `geom` と同じく最初の
使用時に読み込まれます。

**Common Lisp の一部ではありません**。名前は `tokenizer:` 修飾子で参照します。
標準パッケージ以外には何も依存せず、**語彙は常に引数であって、このパッケージが
ファイルを開くことはありません**。そのためすべてのバックエンドとブラウザの
プレイグラウンドで動き、語彙の出どころも問いません（GGUF の
`tokenizer.ggml.tokens` / `merges`、`tokenizer.json` の `vocab` / `merges`、
手で書いたフィクスチャのいずれでも構いません）。

| 関数 | 例 | 結果 |
|------|----|------|
| `tokenizer:make-bpe` | `(tokenizer:make-bpe tokens merges :kind :smollm)` | 与えた語彙とランク付きマージリストによるバイトレベル BPE トークナイザ |
| `tokenizer:make-sentencepiece` | `(tokenizer:make-sentencepiece pieces scores)` | SentencePiece 形式のトークナイザ。スコア付きピースをスコア順に貪欲マージ |
| `tokenizer:encode` | `(tokenizer:encode tk "hello world" :bos t)` | トークン id 列。特殊トークンは事前トークナイズより前に丸ごと一致 |
| `tokenizer:decode` | `(tokenizer:decode tk ids)` | id 列**全体**が表すテキスト。`tokenizer:encode` と往復する |
| `tokenizer:decode-bytes` | `(tokenizer:decode-bytes tk ids)` | 生バイト列。1 トークンずつデコードする生成ループ向け |
| `tokenizer:pre-tokenize` | `(tokenizer:pre-tokenize :qwen2 "a 12")` | 5 種類の形のいずれかでテキストを事前トークンに分割 |
| `tokenizer:token-string` | `(tokenizer:token-string tk 17)` | 語彙が id に与えているバイトレベル表記 |
| `tokenizer:token-id` | `(tokenizer:token-id tk "hello")` | トークン文字列の id、なければ `nil` |
| `tokenizer:vocabulary-size` | `(tokenizer:vocabulary-size tk)` | 語彙が定義している id の個数 |
| `tokenizer:bos-id` | `(tokenizer:bos-id tk)` | `:bos t` が付ける開始 id、なければ `nil` |
| `tokenizer:eos-id` | `(tokenizer:eos-id tk)` | `:eos t` が付ける終了 id、なければ `nil` |
