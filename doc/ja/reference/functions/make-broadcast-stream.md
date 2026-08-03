# make-broadcast-stream

`(make-broadcast-stream &rest streams)`

書き込みのすべてを、指定した順に各コンポーネントストリームへ配る出力ストリームを
返します。コンポーネントがない場合は書き込みを捨てるシンクになります (null 出力
ストリームの CL イディオム)。

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
[Gray ストリーム](../../guides/gray-streams.md)なので、Gray ストリームにディスパッチ
する演算子だけが使えます: [`format`](../macros/format.md)、[`princ`](princ.md)、
[`prin1`](prin1.md)、[`write-string`](write-string.md)、`write-char` です。
[`terpri`](terpri.md)、[`fresh-line`](fresh-line.md)、[`write-line`](write-line.md)、
[`print`](print.md)、[`force-output`](force-output.md)、
[`finish-output`](finish-output.md)、[`close`](close.md) はここではそのプロトコルに
含まれず、ブロードキャストかどうかによらず Gray ストリームに対してシグナルを発生
させます。改行は `(format s "~%")` で書いてください。コンポーネントのないシンクは
通常の文字列出力ストリームなので、これらすべてを受け付けます。
