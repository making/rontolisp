# use

`(use 'clause ...)`

namespace をロードし、クォートされた各節が列挙する名前を refer して、`nil` を返します -- `(:only [...])` が名前を狭め、`(:exclude [...])` がそこから差し引く、`ns` が行うのと同じ refer の配線です。解決するのは `clojure.string`、`clojure.java.io`（`reader` のみ）、`clojure.test` で、未知の namespace はエラーです。クォートされていないベクター節も受け付けますが、本物の Clojure はそれを拒否します。プレフィックスリスト `'(prefix [sub ...])` は各メンバーをプレフィックスの下に配線します（クォートの有無は問いません）。

```clojure
(use '[clojure.string :only [upper-case]])
(println (upper-case "hi")) ; HI
```
