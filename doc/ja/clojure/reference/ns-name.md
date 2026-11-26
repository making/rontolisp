# ns-name

`(ns-name ns)`

`clojure.core/ns-name` です。名前空間 `ns`（シンボルなら [the-ns](the-ns.md) が見つける
名前空間）の名前をシンボルで返します。値としては1引数の関数です。

```clojure
(println (ns-name *ns*) (symbol? (ns-name *ns*)) (map ns-name ['user]))
```

```
user true (user)
```
