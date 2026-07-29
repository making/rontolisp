# make-string-output-stream

`(make-string-output-stream)`

新しい文字列出力ストリームを返します。書き込まれた内容を蓄積する出力ストリームで、`get-output-stream-string` で読み出します。`with-output-to-string` が内部で作るものを明示的に作る形で、ストリームが 1 つの式より長生きする必要がある場合 (`defstruct` スロットの `:initform` など) に使います。CL の `:element-type` キーワード引数は受け付けますが無視されます。rontolisp のストリームはすべて文字ストリームです。

```lisp
(let ((s (make-string-output-stream)))
  (write-string "ab" s)
  (princ 12 s)
  (get-output-stream-string s)) ; => "ab12"
```
