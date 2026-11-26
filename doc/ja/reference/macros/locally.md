# locally

`(locally declaration... form...)`

本体を `progn` として評価します。rontolisp では宣言はどこでもパースされるだけの no-op なので([`declare`](declare.md)/[`the`](the.md))、`locally` は先頭の `declare` フォームを取り除いて残りを評価するだけです — 本物の Common Lisp の宣言をスコープするために `locally` を使うコードはそのまま動きます。

```lisp
(locally
  (declare (optimize (speed 3)))
  (+ 40 2)) ; => 42
```
