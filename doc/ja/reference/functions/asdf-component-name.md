# asdf:component-name

`(asdf:component-name component)`

リーダー: 定義に書かれたコンポーネントの名前を返します。シンボルは小文字化します。
コンポーネントの `:pathname` も、囲むモジュールも名前を変えません。
`(:module "m" :components ((:file "two")))` の子の名前は `"two"` です。

```lisp
(asdf:defsystem :demo-cn :components ((:file "main")))
(asdf:component-name (asdf:find-system :demo-cn)) ; => "demo-cn"
```

## バックエンドサポート

4 つすべてのバックエンドで、あらゆるコンポーネントオブジェクト
（[`asdf:find-system`](asdf-find-system.md) のシステムと、その
[`asdf:component-children`](asdf-component-children.md)）に対して動作します。
