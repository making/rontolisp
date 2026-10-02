# meta

`(meta obj)`

[with-meta](with-meta.md)（またはコレクションリテラルのリーダーメタデータ）で `obj` に
付いたメタデータマップを返します。持たなければ `nil` です。

```clojure
(println (meta (with-meta {:a 1} {:source :db}))) ; {:source :db}
(println (meta ^:flag [1]))                      ; {:flag true}
```
