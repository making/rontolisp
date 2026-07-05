# with-input-from-string

`(with-input-from-string (stream string) body...)`

`stream` を `string` から読み取る入力ストリームに束縛して本体フォームを評価し、最後のフォームの値を返します。`read-line` は文字列を 1 行ずつ消費し、終端で nil を返します。`read` は 1 行につき 1 つのデータをパースします（ファイルストリームの `read` と同じく行指向のため、行内の最初のデータ以降は読み飛ばされます）。3 つすべてのバックエンドで動作します。

```lisp
(with-input-from-string (s "(1 2 3)")
  (read s)) ; => (1 2 3)
```
