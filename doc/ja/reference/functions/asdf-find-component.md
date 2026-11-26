# asdf:find-component

`(asdf:find-component base path &key registered)`

名前でコンポーネントを探します。`base` が nil のとき `path` はシステムの指定子
(またはそれで始まるリスト) で、どこにも定義のない名前には nil を返します。`base` が
システム、その指定子、モジュールのいずれかのとき、`path` は子の名前か、木の階層ごとに
1 つずつ並べて子に至る名前のリストです。`nil` なら `base` 自身を返します。不明な名前には
nil を返し、ファイルより下の名前はエラーを通知します。`:registered` が真のとき、
システムの指定子は登録済みのシステムだけを指し、探索は行いません。

```lisp
(asdf:defsystem :found :components ((:file "main") (:module "m" :components ((:file "two")))))
(asdf:component-name (asdf:find-component :found "main")) ; => "main"
(asdf:component-name (asdf:find-component :found "m")) ; => "m"
(asdf:component-name (asdf:find-component :found '("m" "two"))) ; => "two"
(asdf:find-component :found "nope") ; => NIL
```

## バックエンドサポート

4 つすべてのバックエンドで動作します。
