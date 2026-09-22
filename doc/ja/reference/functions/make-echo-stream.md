# make-echo-stream

`(make-echo-stream input-stream output-stream)`

読み取ったものを出力コンポーネントにも書き込む 2way ストリーム (対話エコーの
イディオム)。

```lisp
(let ((out (make-string-output-stream)))
  (let ((s (make-echo-stream (make-string-input-stream "ab") out)))
    (list (read-char s) (read-line s) (get-output-stream-string out))))
; => (#\a "b" "ab")
```

入力側は [Gray ストリーム](../../guides/gray-streams.md)なので、`read-line` や
`read-char` でディスパッチされ、読み取った各文字は途中で出力コンポーネントにもエコー
されます。`write-char` / `write-string` は出力側へ直接届きます。コンポーネントは
[`echo-stream-input-stream`](echo-stream-input-stream.md) と
[`echo-stream-output-stream`](echo-stream-output-stream.md) で取り出せます。

各コンポーネントは正しい方向のストリームでなければなりません。入力側が入力ストリームでない場合、
または出力側が出力ストリームでない場合は `type-error` を通知します。
