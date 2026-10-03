# volatile?

`(volatile? x)`

`clojure.core/volatile?`: volatile（`volatile!`）なら `true` を返します。atom・ref・agent は `false` です。値としては1引数の関数です。

```clojure
(println (volatile? (volatile! 1)) (volatile? (atom 1)))  ; true false
```
