# asdf:component-name

`(asdf:component-name component)`

リーダー: コンポーネントの小文字正規形の名前を返します。システムではシステム名、
ソースファイルではシステムからの相対パスから `.lisp` 拡張子を除いたものです。

```lisp
(asdf:defsystem :demo-cn :components ((:file "main")))
(asdf:component-name (asdf:find-system :demo-cn)) ; => "demo-cn"
```

## バックエンドサポート

4 つすべてのバックエンドで、あらゆるコンポーネントオブジェクト
（[`asdf:find-system`](asdf-find-system.md) のシステムと、その
[`asdf:component-children`](asdf-component-children.md)）に対して動作します。
