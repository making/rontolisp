# make-synonym-stream

`(make-synonym-stream symbol)`

特殊変数 `symbol` が **その操作の時点で** 保持しているストリームへ、すべての操作を転送するストリームを返します。したがって、後から変数を再束縛すると、先に構築されたシノニムストリームの転送先も変わります。標準ストリームの現在の転送先に追随する既定値を持つ `defvar` を書くのが典型的な使い方です。

戻り値は指定子ではなくストリームの **値** です。真であり、[`streamp`](streamp.md) / [`input-stream-p`](input-stream-p.md) / [`output-stream-p`](output-stream-p.md) は `t` を返し、[`synonym-stream-symbol`](synonym-stream-symbol.md) でシンボルを取り出せます。[`close`](close.md) はシノニム自体を閉じる (実際には何もしない) ので `t` を返します。

Gray ストリームはどちら側にも置けます。Gray 出力ストリームに渡したシノニムストリームは書き込みが通り、変数が Gray ストリームを保持しているシノニムストリームもそこへ届きます。[Gray ストリーム](../../guides/gray-streams.md) を参照してください。

```lisp
(defvar *report-output* (make-synonym-stream '*standard-output*))
(write-string "hello" *report-output*) ; => "hello"
```
