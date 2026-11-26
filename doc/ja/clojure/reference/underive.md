# underive

`(underive tag parent)`
`(underive h tag parent)`

`tag` から `parent` への辺を削除します。2 引数形式はグローバルの階層を書き換えて `nil` を返し、3 引数形式は更新済みの階層値を返します。

```clojure
(derive :c :p)
(underive :c :p)
(println (isa? :c :p)) ; false
```
