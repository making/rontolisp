# read-line

`(read-line &optional stream)`

テキストを 1 行読み取り、末尾の改行を取り除いた文字列として返します(CRLF 行末のキャリッジリターンも Java の `BufferedReader.readLine` と同様に取り除かれるため、[`rontolisp:tcp-connect`](rontolisp-tcp-connect.md) のソケット経由の HTTP のような CRLF 終端の入力も通常の行として読めます)。引数がない場合は標準入力から読み取ります。`open` または `with-open-file` で開いたストリームを与えると、そのストリームから次の行を読み取ります。入力の終端ではエラーを通知せず `nil` を返します。3 つすべてのバックエンドで動作します。`read` と異なり、S 式として解析せずに生の行を返します。

```console
(print (read-line))
```

標準入力に `hello world` と入力すると、`read-line` は文字列 `"hello world"` を返します。入力が尽きると `nil` を返し、これはファイルを 1 行ずつ読むときの一般的なループ終了判定になります。
