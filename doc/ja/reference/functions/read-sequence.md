# read-sequence

`(read-sequence sequence stream &key start end)`

バイナリ入力ストリームから読み込んだバイトで `sequence`（`make-array` で作成した 1 次元配列）を埋め、埋められなかった最初の要素のインデックス（充填位置）を返します。読み込みはインデックス `:start`（デフォルト 0）から始まり、インデックス `:end`（デフォルトは配列長）の手前、またはファイル終端のいずれか早い方で止まります。`:start`/`:end` キーワードはリテラルでなければなりませんが、その値は任意の式で構いません。3 つのバックエンドすべてで動作します。`read-byte` のループに展開されるため、`:element-type '(unsigned-byte 8)` で開いたストリームが必要です。

ファイルシステムに触れるため、`read-sequence` はここでは実行可能な例ではなく静的に示します。

```console
(let ((buf (make-array 8)))
  (with-open-file (in "data.bin" :element-type '(unsigned-byte 8))
    (read-sequence buf in))  ; => 4 when data.bin has 4 bytes
  (aref buf 0))              ; => the first byte
```

返り値が配列長より小さい場合はファイルが途中で終わったことを意味し、充填位置以降の要素は元の値を保持します。
