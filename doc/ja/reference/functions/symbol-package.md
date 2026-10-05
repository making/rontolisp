# symbol-package

`(symbol-package symbol)`

lite 版: [`find-package`](find-package.md) と同じキーワード形式を返すため、両者は `eq` で比較できます。キーワードには `:keyword`、パッケージ修飾されたシンボルにはその修飾子、標準シンボル(`t`、`nil`、およびエクスポートのみの標準名を含む)には `:cl`、それ以外には `:cl-user`、アンインターンされた (`#:`) シンボル -- あるいは [`unintern`](unintern.md) がホームパッケージから取り除いたシンボル -- には `nil` を返します。コンパイルされたバックエンドはホームをシンボルの綴りから読み、標準シンボルと `cl-user` のシンボルを、そのときプログラムが持つ標準名の表で区別します。unintern されたシンボルのホームはそのまま読みます。

```lisp
(symbol-package :foo) ; => :KEYWORD
```

```lisp
(symbol-package t) ; => :CL
```

```lisp
(symbol-package (read-from-string "mapcar")) ; => :CL
```
