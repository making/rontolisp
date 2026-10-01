# descendants

`(descendants tag)`
`(descendants h tag)`

階層 -- グローバルのもの、2 引数形式では `h` -- における `tag` のすべての子孫を、推移的に含むセットを返します。

```clojure
(derive :c :p)
(derive :d :p)
(println (count (descendants :p))) ; 2
```
