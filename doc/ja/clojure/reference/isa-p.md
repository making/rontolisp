# isa?

`(isa? child parent)`
`(isa? h child parent)`
`(isa? vec1 vec2)`

`child` が `parent` と関係するかどうかを返します。等価、要素ごとのベクター派生（各要素が対応するものへ `isa?`）、または階層 -- グローバルのもの、3 引数形式では `h` -- を通る祖先 membership のいずれかです。`true` か `false` を返し、multimethod のディスパッチ検索はこれの上に組み立てられています。

```clojure
(derive :c :p)
(println (isa? :c :p)) ; true
(println (isa? :c :c)) ; true
(println (isa? :p :c)) ; false
```
