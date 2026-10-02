# require

`(require 'clause ...)`

namespace をロードし、各節の `:as` alias と `:refer` された名前を配線して、`nil` を返します -- `ns` が行うのと同じ配線を、クォートされた libspec でトップレベルに綴ったものです。解決するのは `clojure.string`、`clojure.java.io`（`reader` のみ）、`clojure.test` で、未知の namespace はエラーです。クォートされていないベクター節も受け付けますが、本物の Clojure はそれを拒否します。プレフィックスリスト `'(prefix [sub ...])` は各メンバーをプレフィックスの下に配線します（クォートの有無は問いません）。

```clojure
(require '[clojure.string :as s])
(println (s/join "," ["a"])) ; a
```
