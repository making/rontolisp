# finish-output

`(finish-output &optional stream)`

[`force-output`](force-output.md) と同じ操作です。指定された出力ストリームを書き出し、nil を返します。Common Lisp では両者は区別されます (`finish-output` は出力先がデータを受け取り終えるまで待ちます) が、rontolisp では書き出し後の書き込みはすべて同期的なので、2 つの名前は 1 つの振る舞いを指します。

```lisp
(progn (princ "buffered") (finish-output)) ; => NIL
```
