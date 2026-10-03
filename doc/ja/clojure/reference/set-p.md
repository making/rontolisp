# set?

`(set? x)`

`clojure.core/set?`: セット（ソート済みのものも含む）なら `true` を返します。値としては1引数の関数です。

```clojure
(println (set? #{1}) (set? {}) (set? [1]))  ; true false false
```
