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
