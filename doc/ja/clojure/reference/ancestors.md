# ancestors

`(ancestors tag)`
`(ancestors h tag)`

階層 -- グローバルのもの、2 引数形式では `h` -- における `tag` のすべての祖先を、推移的に含むセットを返します。

```clojure
(derive :c :p)
(derive :p :q)
(println (ancestors :c)) ; #{:p :q}
```
