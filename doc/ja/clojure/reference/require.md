# require

`(require clause)`

namespace をロードし、節の `:as` alias と `:refer` された名前を配線して、`nil` を返します -- `ns` が行うのと同じ配線を、quote された節のフォームでトップレベルに綴ったものです。解決するのは `clojure.string` のみで、未知の namespace はエラーです。

```clojure
(require '[clojure.string :as s])
(println (s/join "," ["a"])) ; a
```
