# モデルチェックポイントを読む (`gguf`, `safetensors`)

公開されている言語モデルは、ディレクトリの中の 3 つのものです。重み、トークナイザが
学習に使った語彙、そして重みの結線を決めるいくつかのハイパーパラメータです。
rontolisp はこの 3 つを、同梱の 4 つのパッケージで読みます。いずれも rontolisp 自身で
書かれ、[`linalg`](linear-algebra.md) や [`geom`](solid-modeling.md) と同じく最初の
使用時に読み込まれます。[`gguf`](../reference/functions/gguf.md) と
[`safetensors`](../reference/functions/safetensors.md) が 2 つのコンテナリーダ、
[`checkpoint`](../reference/functions/checkpoint.md) が両者が共有するステージング側、
[`tokenizer`](../reference/functions/tokenizer.md) がファイルの持つ語彙をトークン id
に変えます。Python も変換ステップも外部依存もありません。Hugging Face のモデルページ
がダウンロードしたものを、置いた場所のまま読みます。

このページは、この 4 つがどう組み合わさるかを扱います。以下の順序は、読み手がそれらに
出会う順序です。チェックポイントを選び、重みに触れずにメタデータを読み、ファイルが
持つトークナイザを組み立て、バックエンドが扱える幅でテンソルをステージングし、
チェックポイントが期待するプロンプトを組み立てる、という順です。

## 2 つのコンテナ

**GGUF は 1 ファイルです。**ハイパーパラメータ・トークナイザ・重みが、公開者が選んだ
幅のまま一緒に入っています --
`TinyLlama-1.1B-Chat-v1.0-f16.gguf`、`Qwen3.5-0.8B-BF16.gguf`、
`SmolLM2-135M-Instruct-Q8_0.gguf` といった具合です。`*-GGUF` リポジトリが配布し、
`llama.cpp` が実行するのがこれです。`gguf:read` は 1 つのハンドルからすべての問いに
答えます。

**safetensors のチェックポイントはディレクトリです。**`model.safetensors` --
またはシャードファイルを列挙する `model.safetensors.index.json` -- が持つのは重みだけ
です。ハイパーパラメータは隣の `config.json`、語彙は `tokenizer.json` と
`tokenizer_config.json` で、どれも
[`rontolisp:json-parse`](../reference/functions/rontolisp-json-parse.md)
で読める普通の JSON ファイルです。Hugging Face のモデルページがダウンロードするのは
これであり、公開者自身が学習してアップロードしたファイルもこれです。同じモデルの
GGUF は、その変換物です。

この違いが 2 つの読み方の形をそのまま決めます。GGUF は 1 回の呼び出しといくつかの
アクセサ、safetensors のチェックポイントは自分で組み合わせる 3 つの読み取りで、
しかもその半分どうしが食い違うことがあります -- それがこのページ末尾の
[チャットテンプレート](#the-chat-template-is-the-checkpoints)の話題です。

どちらの形式も、ヘッダに続いてヘッダ末尾からのオフセットにテンソルのバイト列が並び
ます。GGUF のヘッダはキー/値ブロックとテンソルディレクトリ、safetensors のヘッダは
リトルエンディアンの `u64` 長とその長さの JSON
`{ "<name>": { "dtype": "BF16", "shape": [rows, cols], "data_offsets":
[begin, end] }, ... }` です。どちらのリーダーもファイルを先頭から順に歩き、要求され
なかったものは `checkpoint:skip-bytes` で読み飛ばします。ストリームが位置を答える
場合はそこまでシークし、そうでない場合は読み進めて飛ばします。

## メタデータはタダで読める

`(gguf:read path :metadata-only t)` はテンソルディレクトリの直後で止まります。ハイパー
パラメータもトークナイザ全体もすでにそこにあるので、ギガバイト側には一切触れません。
ダウンロードしたばかりのチェックポイントを調べる方法であり、その語彙を取り出す方法で
もあります。

```console
CL-USER> (defparameter *m* (gguf:read "SmolLM2-135M-Instruct-f16.gguf" :metadata-only t))
*M*
CL-USER> (list (gguf:version *m*)
               (gguf:metadata-value *m* "general.architecture")
               (gguf:metadata-value *m* "llama.block_count")
               (length (gguf:tensor-names *m*)))
(3 "llama" 30 272)
```

全体を眺めたいときは
[`gguf:metadata`](../reference/functions/gguf-metadata.md) がキー/値ブロック全体を
ハッシュテーブルで返します。
[`gguf:metadata-value`](../reference/functions/gguf-metadata-value.md) は 1 つのキーと、
本当にキーが無いときだけ返る既定値を取ります。
[`gguf:tensor-info`](../reference/functions/gguf-tensor-info.md) は 1 つのテンソルの
ディレクトリエントリで、その dims はファイル自身の並びではなく**行優先**です。

safetensors 側の同じ読み取りは
[`safetensors:header`](../reference/functions/safetensors-header.md)（JSON ヘッダを
パースし、データの開始位置を返す）と
[`safetensors:entries`](../reference/functions/safetensors-entries.md)（それを
ファイル順の `(name dtype shape begin end)` にする）です。ハイパーパラメータは
`config.json` の `rontolisp:json-parse` という別の読み取りになります。

**テンソルを飛ばして節約できるのはメモリ、そしてストリームが位置を答える場合は I/O もです。**`gguf:read`
の `:only` はテンソル名のリスト、`safetensors:read` の `:only` は名前に対する述語です。
どちらでも要らないテンソルはステージングも変換もされず、シークできるストリームでは
読み取り自体が発生しません。マルチモーダルなチェックポイントの視覚タワーは、飛ばせば
コストゼロです。ただし位置を持たないストリームでは従来通りバイト列を読むコストを払い、
有界の読み取りで歩きます。

## チェックポイントが持つトークナイザ

出回っている語彙は 2 種類で、`tokenizer:` はその両方を 1 つの `tokenizer:encode` /
`tokenizer:decode` の背後に置きます。

- **バイトレベル BPE**、GPT-2 の形です。id で引くトークン文字列と、ランクの良い順に
  並んだマージリストからなります。SmolLM2、Qwen 2.5 / 3 / 3.5、Llama 3、LFM2.5 が
  使います。
  [`tokenizer:make-bpe`](../reference/functions/tokenizer-make-bpe.md) が作ります。
- **SentencePiece 形式**。ピースごとにスコアが付き、スコア順に貪欲にマージします。
  Llama 2 と TinyLlama が使います。
  [`tokenizer:make-sentencepiece`](../reference/functions/tokenizer-make-sentencepiece.md)
  が作ります。

**このパッケージはファイルを開きません。**語彙は常に引数です。だからこそ `tokenizer:`
は標準パッケージ以外に何も依存せず、ブラウザのプレイグラウンドを含むすべての
バックエンドで動きます。語彙の出どころは問いません -- チェックポイントのものでも、
手で書いたものでも構いません。

```lisp
(defparameter *tk*
  (tokenizer:make-bpe
   #("<|endoftext|>" "h" "e" "l" "o" "Ġ" "w" "r" "d"
     "he" "hel" "hell" "hello" "Ġw" "Ġwo" "Ġwor" "Ġworl" "Ġworld")
   '("h e" "he l" "hel l" "hell o" "Ġ w" "Ġw o" "Ġwo r" "Ġwor l" "Ġworl d")
   :specials '("<|endoftext|>") :bos 0 :eos 0))
(tokenizer:decode *tk* (tokenizer:encode *tk* "hello world"))
; => "hello world"
```

GGUF からなら、語彙は 2 つのコンストラクタが取る形のまま出てきます。
[`gguf:tokenizer-fields`](../reference/functions/gguf-tokenizer-fields.md) が返すのは
`:model`（`"gpt2"` = マージ付きバイトレベル、`"llama"` = スコア付き SentencePiece
形式）、`:pre`、`:tokens`、`:scores`、`:merges`、`:token-type`、`:bos`、`:eos` の
plist で、解釈せずそのまま差し出します。`:metadata-only t` の時点でこれらはすべて
揃っているので、チェックポイントのトークナイザを取り出すのに重みを読むことはありま
せん。

```console
CL-USER> (let* ((fields (gguf:tokenizer-fields *m*))
                (tk (tokenizer:make-bpe (getf fields :tokens) (getf fields :merges)
                                        :kind (getf fields :pre))))
           (tokenizer:encode tk "Once upon a time"))
(6403 1980 253 655)
```

safetensors のディレクトリからなら、`tokenizer.json` 自身の `vocab` と `merges` に
`added_tokens` を埋めたもの、そして残りは `tokenizer_config.json` です。

**事前トークナイザはデータでない側の半分**なので、独立した `:kind` 引数であり、
[`tokenizer:pre-tokenize`](../reference/functions/tokenizer-pre-tokenize.md) として
公開されています。`:gpt2`、`:smollm`（`\p{N}` の文字をすべて 1 文字ずつ切り出す）、
`:llama3`（数字は 3 桁ずつ）、`:qwen2`（数字は 1 桁ずつ）、`:qwen35`（結合文字は
その文字と一緒に残る）、あるいは GGUF 自身の `tokenizer.ggml.pre` 文字列
（`make-bpe` はそのまま受け取ります）です。id が合うのは、切り方が合ってからです。

```lisp
(list (tokenizer:pre-tokenize :qwen2 "in 2025")
      (tokenizer:pre-tokenize :llama3 "in 2025"))
; => (("in" " " "2" "0" "2" "5") ("in" " " "202" "5"))
```

`:specials` は、事前トークナイズより前に丸ごと一致させるトークン文字列のリストです。
ファイルが宣言する追加トークンは、special のフラグが付いていてもいなくても**すべて**
渡してください。Qwen 3 は `<think>` と `</think>` をフラグなしで同梱しており、
フラグの付いたものだけを取ったリーダーは、チャットプロンプトの think ブロックを
`<th` `ink` `>` として食わせます -- モデルが 1 つの id を期待するところに 3 つです。

## 読み込める幅

チェックポイントはある幅で公開されますが、すべての幅がどこでも読めるわけではありま
せん。

**F32 / F16 / BF16 はパックされた浮動小数点配列になります。**行き先は
`:element-type` が決めます。`'single-float`（既定）、`'double-float`、あるいは
インタプリタと JVM でのみ `'bfloat16` です -- それ以外のバックエンドはこの幅を名指し
で拒否します。BF16 テンソルを `'bfloat16` の行き先に読むのが、唯一まったく変換の
起きない組み合わせです。ファイル自身の 1 要素 2 バイトを、1 回の転送で読みます。
それ以外はストリームしながら拡張（または縮小）されるので、2.2 GB の BF16 ファイルは
4.4 GB の単精度になり、あとは数メガバイトあれば足ります。

**Q8_0 のテンソルは量子化行列になります** --
[`rontolisp:quantize`](../reference/functions/rontolisp-quantize.md) のブロックを
そのまま置き、自身のバイト列を 1 回の転送で読み、`:element-type` は適用されません。
GGUF のみ、かつインタプリタと JVM のみです。WASM のバックエンドはそのテンソルで
シグナルします。読み込んだあとで**分割**が必要なテンソル -- 融合された
`query | gate` の射影など -- は
[`rontolisp:quantized-rows`](../reference/functions/rontolisp-quantized-rows.md)
で分割します。指定した行をブロックのまま集めて新しい行列にするので、分割後も
量子化されたままで、値が展開されることはありません。

**それ以外の量子化型は、その本体を要求されたときに名指しで拒否され、それより早くは
拒否されません。**Q4_K_M のチェックポイントでも、開くこと・テンソルディレクトリ全体
を見ること・語彙を取り出すことはできます。失敗するのは、渡せないテンソルを要求した
ところだけです。safetensors のリーダーは同じ規則を裏返しに述べます。F32 / F16 / BF16
以外の dtype は、テンソル名と dtype を挙げたエラーになります。

幅はモデルを走らせ始めれば細部ではありません。デコードの 1 ステップはモデルの重みを
ちょうど 1 回ずつ流すので、1 トークンのコストは重みが占めるバイト数そのものであり、
それを半分にすることには言葉どおりの価値があります。その重みを掛ける側は
[`--simd`](simd-acceleration.md)、[`--blas`](blas-acceleration.md)、
[`--gpu`](gpu-acceleration.md) の話題です。

## `checkpoint` がステージングするもの

[`checkpoint`](../reference/functions/checkpoint.md) を直接使うのは、この 2 つの
パッケージが扱わないコンテナのリーダーを書くときだけです。それでも何を強制している
かは知る価値があります。その背後にある 3 つの事実が、手書きのローダーが壊れる 3 通り
の壊れ方そのものだからです。

`checkpoint:make-tensor` が唯一の確保経路です。`make-array :element-type` は知らない
型に対して boxed な配列を返すので、手で確保したテンソルは黙って 1 要素 8 バイトを
食いかねません。`make-tensor` は得たものを検証します。
`checkpoint:stage-float-bits` が受け取るのはストリーム・個数・`:float16` または
`:bfloat16`・行き先であって、ステージング済みのベクタ丸ごとではありません。パックされ
た `(unsigned-byte 16)` ベクタもインタプリタと JVM では同じ 1 要素 8 バイトを食うので、
丸ごとステージングしたテンソルは一時領域にファイルサイズの 4 倍を要求してしまうから
です。実際には 100 万要素ずつ、あらゆるファイルのあらゆるテンソルで使い回す 1 本の
バッファを通して読み、各チャンクを `rontolisp:widen-float-bits` で拡張します。
`checkpoint:stage-float32` は F32 テンソルを直接読み込み、`checkpoint:skip-bytes` は
先頭からの走査が要らないものを読み飛ばす手段です。

## どのバックエンドで動くか

`tokenizer:` はどこでも動きます。ブラウザのプレイグラウンドでもです。語彙がファイル
ではなく引数だからです。

2 つのコンテナリーダーが必要とするのはファイルシステムだけです。F32・F16・BF16 の
テンソルは、4 つのバックエンドすべてで、`--simd` の有無にかかわらず、同じパック
された浮動小数点配列になります。`examples/llm/` にチェックインされたフィクスチャが、
そのことを各バックエンドで固定しています。

狭いものが 2 つあり、どちらも黙って別のことをするのではなく名指しで拒否します。
`'bfloat16` の要素型と Q8_0 の量子化行列は、インタプリタと JVM です。どれも GPU は
要りません -- `--gpu` が変えるのは重みを何が掛けるかであって、何が読むかではありま
せん。

## チャットテンプレートはチェックポイントのもの

instruct モデルはある形のプロンプトで学習されており、形の違うプロンプトには、何も
言わずに悪く答えます。**権威はチェックポイント自身のテンプレートです。**GGUF は
`tokenizer.chat_template` というメタデータキーとして、Hugging Face のディレクトリは
`tokenizer_config.json` の `chat_template` として持っています。ファミリのものを仮定
する前に、ファイルから読み出してください -- GGUF なら、メタデータのみの読み取りに
対する `(gguf:metadata-value *m* "tokenizer.chat_template")` 一発です。

これが防ぐ失敗は仮想の話ではありません。SmolLM2-Instruct 自身のテンプレートは、最初の
メッセージがシステムターンでない限り、無条件にシステムターン
（`<|im_start|>system`、`You are a helpful AI assistant named SmolLM, trained by
Hugging Face`、`<|im_end|>`）で始まります。一方 LFM2.5 のテンプレートは、同じ 1 つの
ユーザーターンに対してシステムターンをまったく出しません。この 2 つは ChatML の
トークンをすべて共有し、どちらの語彙にも `<|im_start|>` があるので、ひとつの汎用
ChatML の描画は両方に対して正しく見えて、片方には間違っています。そうやって作られた
SmolLM2-Instruct の答えはすべてシステムターンを欠いていましたが、それが見つかったのは
出力を読んだからではなく、チェックポイント自身のテンプレートと差分を取ったからです。
Qwen 3 と 3.5 はさらに 3 つ目の形を持ち込みます -- **空の** `<think>` ブロックが、
そのテンプレートで thinking を切る方法です。

rontolisp は Jinja を描画しないので、プログラムはファミリの描画を format 制御文字列
として持つか、テンプレートをファイルから読み出してそれに従うかのどちらかになります。
いずれにせよ決めるのはファイルです。

## 動く実物

[`examples/llm/`](../../../examples/llm/README.md) がこの道筋全体の実物です。
Karpathy の `llama2.c` を 1 つの Lisp ファイルに移植したもので、`.bin`・GGUF・
Hugging Face の safetensors チェックポイントを、ここで挙げた 4 つのパッケージを通して
読み、ファミリごとに分岐するのではなくレイヤ種別の表で扱います。その README は、
どの公開モデルを、どのバックエンドで走らせ、1 トークンがいくらだったかの記録です。
このページはパッケージの説明で、あちらはそれを使って書かれたプログラムの説明です。
