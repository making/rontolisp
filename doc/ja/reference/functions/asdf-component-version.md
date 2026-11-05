# asdf:component-version

`(asdf:component-version component)`

リーダー: システムの `defsystem` が宣言した `:version` 文字列を返します。宣言が
なければ `nil` です。`.asd` ファイルの `(:read-file-form "version.sexp" :at 0)` と
`(:read-file-line "version.txt" :at 0)` という間接指定は、ASDF と同じく `.asd` の隣の
そのファイルを読みます。ドット区切りの整数として解釈できないものは `nil` です。その他の
書き方、読めない間接指定、`.asd` の外の `defsystem` の間接指定も `nil` です。
コンポーネントファイル自身はバージョンを持ちません。

```lisp
(asdf:defsystem :demo-cv :version "0.9.15" :components ((:file "main")))
(asdf:component-version (asdf:find-system :demo-cv)) ; => "0.9.15"
```

## バックエンドサポート

4 つすべてのバックエンドで、あらゆるコンポーネントオブジェクト
（[`asdf:find-system`](asdf-find-system.md) のシステムと、その
[`asdf:component-children`](asdf-component-children.md)）に対して動作します。
