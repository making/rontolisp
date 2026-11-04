# asdf:find-component

`(asdf:find-component base path)`

名前でコンポーネントを探します。`base` が nil のとき `path` はシステムの指定子
(またはそれで始まるリスト) で、どこにも定義のない名前には nil を返します。`base` が
システムかその指定子のとき、`path` は子の名前か、子に至る名前のリストです。`nil` なら
`base` 自身を返します。不明な名前には nil を返します。

ここではシステムの子は平坦です ([`asdf:component-children`](asdf-component-children.md))。
パスは子の相対パスと、全体またはディレクトリの区切りから照合します。したがって `:module`
自体はこの関数では見つかりません。

```lisp
(asdf:defsystem :found :components ((:file "main") (:module "m" :components ((:file "two")))))
(asdf:component-name (asdf:find-component :found "main")) ; => "main"
(asdf:component-name (asdf:find-component :found '("m" "two"))) ; => "m/two"
(asdf:find-component :found "nope") ; => NIL
```

## バックエンドサポート

4 つすべてのバックエンドで動作します。
