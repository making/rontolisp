# simple-symbol?

`(simple-symbol? x)`

`clojure.core/simple-symbol?`: 名前空間のないシンボルなら `true` を返します（`/` 単独もこれに当たります）。値としては1引数の関数です。

```clojure
(println (simple-symbol? 'a) (simple-symbol? '/) (simple-symbol? 'a/b))  ; true true false
```
