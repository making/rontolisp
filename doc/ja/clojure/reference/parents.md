# parents

`(parents tag)`
`(parents h tag)`

階層 -- グローバルのもの、2 引数形式では `h` -- における `tag` の直接の親のセットを返します。無関係な tag は空セットを返します。

```clojure
(derive :c :p)
(println (parents :c)) ; #{:p}
```
