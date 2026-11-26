# meta

`(meta obj)`

[with-meta](with-meta.md)（またはコレクションリテラルのリーダーメタデータ）で `obj` に
付いたメタデータマップを返します。持たなければ `nil` です。[var](var.md) は最新の定義が
記録したもの（`:doc`、`:arglists`、`:line` など）を返します。

```clojure
(println (meta (with-meta {:a 1} {:source :db}))) ; {:source :db}
(println (meta ^:flag [1]))                      ; {:flag true}
```
