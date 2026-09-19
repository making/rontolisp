# make-concatenated-stream

`(make-concatenated-stream &rest streams)`

コンポーネントを順に読み込む入力ストリーム。各コンポーネントは端に達すると捨てられ
ます。

```lisp
(let ((s (make-concatenated-stream (make-string-input-stream "AB")
                                   (make-string-input-stream "CD"))))
  (list (read-char s) (read-char s) (read-char s) (read-char s) (read-char s nil :eof)))
; => (#\A #\B #\C #\D :EOF)
```

入力側は [Gray ストリーム](../../guides/gray-streams.md)なので、`read-line` や
`read-char` がコンポーネントをまたいで動作します。コンポーネントは
[`concatenated-stream-streams`](concatenated-stream-streams.md) で取り出せます。
