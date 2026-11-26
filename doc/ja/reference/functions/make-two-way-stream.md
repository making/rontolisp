# make-two-way-stream

`(make-two-way-stream input-stream output-stream)`

入力と出力の各コンポーネントを1つにまとめたストリーム。読み込みは入力ストリームから、
書き込みは出力ストリームへ届きます。

```lisp
(let ((out (make-string-output-stream)))
  (let ((s (make-two-way-stream (make-string-input-stream "AB") out)))
    (list (read-char s) (write-string "hi" s) (get-output-stream-string out))))
; => (#\A "hi" "hi")
```

2way ストリームは [Gray ストリーム](../../guides/gray-streams.md)なので、読み書き両方の
プロトコルがディスパッチされます。`read-line` や配列演算子は入力側で動作し、
`write-char` / `write-string` / `format` / print 系は出力側へ届きます。コンポーネントは
[`two-way-stream-input-stream`](two-way-stream-input-stream.md) と
[`two-way-stream-output-stream`](two-way-stream-output-stream.md) で取り出せます。

各コンポーネントは正しい方向のストリームでなければなりません。入力側が入力ストリームでない場合、
または出力側が出力ストリームでない場合は `type-error` を通知します。
