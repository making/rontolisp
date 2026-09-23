# make-broadcast-stream

`(make-broadcast-stream &rest streams)`

書き込みのすべてを、指定した順に各コンポーネントストリームへ配る出力ストリームを
返します。コンポーネントがない場合は空リスト上のブロードキャストストリームに
なります。書き込みは捨てられ (null 出力ストリームの CL イディオム)、ファイル
問い合わせは「何もない」ことへの答えを返します (`file-length` 0、
`file-position` 0、`file-string-length` 1、`stream-external-format` `:default`)。
コンポーネントがある場合は同じ問い合わせが最後のコンポーネントの答えを返します。

```lisp
(let ((a (make-string-output-stream))
      (b (make-string-output-stream)))
  (let ((s (make-broadcast-stream a b)))
    (format s "sync ~A" 42))
  (list (get-output-stream-string a) (get-output-stream-string b))) ; => ("sync 42" "sync 42")
```

```lisp
(let ((s (make-broadcast-stream)))
  (write-string "discarded" s)
  :done) ; => :DONE
```

コンポーネントを**持つ**ブロードキャストストリームは
[Gray ストリーム](../../guides/gray-streams.md)なので、出力プロトコル全体が使えます:
[`format`](../macros/format.md)、[`princ`](princ.md)、[`prin1`](prin1.md)、
[`print`](print.md)、[`write-string`](write-string.md)、`write-char`、
[`terpri`](terpri.md)、[`fresh-line`](fresh-line.md)、[`write-line`](write-line.md)、
[`force-output`](force-output.md)、[`finish-output`](finish-output.md)、
[`clear-output`](clear-output.md)、[`close`](close.md) です。
ブロードキャストストリームは桁位置を追跡しないため行頭かどうかを判断できず、
`fresh-line` は最後のコンポーネントの答えを返し、コンポーネントがない場合は
nil を返します。
