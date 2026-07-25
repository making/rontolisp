# symbol-package

`(symbol-package symbol)`

lite 版: [`find-package`](find-package.md) と同じキーワード形式を返すため、両者は `eq` で比較できます。キーワードには `:keyword`、パッケージ修飾されたシンボルにはその修飾子、標準シンボルには `:cl`、それ以外には `:cl-user`、アンインターンされた (`#:`) シンボルには `nil` を返します。コンパイルされたバックエンドは実行時にパッケージレジストリを持たないため `cl` と `cl-user` を区別できず、どちらにも `:cl-user` を返します。

```lisp
(symbol-package :foo) ; => :KEYWORD
```
