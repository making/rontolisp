# comp

`(comp fns...)`

合成関数を返します。右端が引数を広げ、各外側が1つの結果を包みます。0個なら `identity`、
1個ならそれ自身です。値としては実行時に関数リストを合成するので `(apply comp fns)` が動きます。

```clojure
(println ((comp inc inc) 5)) ; 7
(println (map (comp inc inc) [1 2])) ; (3 4)
```
