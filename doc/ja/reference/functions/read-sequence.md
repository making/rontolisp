# read-sequence

`(read-sequence sequence stream &key start end)`

`stream` から読み込んだ要素で `sequence`（`make-array` で作成した 1 次元配列）を埋め、埋められなかった最初の要素のインデックス（充填位置）を返します。読み込みはインデックス `:start`（デフォルト 0）から始まり、インデックス `:end`（デフォルトは配列長）の手前、またはファイル終端のいずれか早い方で止まります。`:start`/`:end` キーワードはリテラルでなければなりませんが、その値は任意の式で構いません。

どの要素を読むかは**バッファ**が決めます。文字ベクタ（`(make-array n :element-type 'character)` や `make-string` が作るもの）はテキストストリームから文字で埋められ、それ以外の配列は `:element-type '(unsigned-byte 8)` で開いたストリームからバイトで埋められます。要素型は `(make-array n :element-type (stream-element-type s))` のように計算された値でも構いません。

```lisp
(with-input-from-string (s "abcdef")
  (let ((buf (make-array 4 :element-type 'character)))
    (list (read-sequence buf s) buf))) ; => (4 "abcd")
```

ファイルシステムに触れるため、バイナリ形式はここでは実行可能な例ではなく静的に示します。

```console
(let ((buf (make-array 8)))
  (with-open-file (in "data.bin" :element-type '(unsigned-byte 8))
    (read-sequence buf in))  ; => 4 when data.bin has 4 bytes
  (aref buf 0))              ; => the first byte
```

返り値が配列長より小さい場合は入力が途中で終わったことを意味し、充填位置以降の要素は元の値を保持します。

## パックドバッファ: 生のバイナリ要素を一括で

バッファが**パックド**配列 -- 任意ランクのパックド浮動小数点配列（`:element-type 'single-float` / `'double-float`、`#f(...)` / `#d(...)`）またはパックド整数ベクタ（`:element-type '(unsigned-byte 8)`、`16`、`32`）-- の場合、`read-sequence` はその要素をバイナリストリームから**生のリトルエンディアンのバイナリ**として、1 バイトずつのループではなく一括転送で読み込みます。single-float は IEEE-754 表現の 4 バイト、double-float は 8 バイト、`(unsigned-byte 16)` は 2 バイト、といった具合です。ランク 2 やランク 3 のパックド浮動小数点配列は行優先順で埋められます（`:start`/`:end` は要素数で数え、`:end` の既定値は総要素数です）。重み行列、numpy の `.npy` ペイロード、C 構造体のダンプなどはこうして読み込みます。`make-array` と `read-sequence` を 1 回ずつ、どのバックエンドでも memcpy の速さで -- llama2 のチェックポイントの 1500 万個の float は約 0.2 秒で読み込めます。ファイル終端で要素の途中まで残ったバイトは格納も計数もされません。

```console
(with-open-file (in "weights.bin" :element-type '(unsigned-byte 8))
  (let ((w (make-array '(288 288) :element-type 'single-float :initial-element 0.0)))
    (read-sequence w in)))  ; => 82944 -- 288*288 little-endian float32s, row-major
```

上記の `read-byte` ループで埋められるのは一般（ボックス化）配列だけです。255 より大きい整数を要素として読みたいプログラムはパックド `(unsigned-byte 16|32)` ベクタを使ってください -- 一般ベクタは相変わらず要素ごとに 1 バイトを受け取ります。
