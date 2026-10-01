# use

`(use 'clause ...)`

namespace をロードし、クォートされた各節が列挙する名前を refer して、`nil` を返します -- `(:only [...])` が名前を狭め、`ns` が行うのと同じ refer の配線です。解決するのは `clojure.string` と `clojure.java.io`（`reader` のみ）で、未知の namespace はエラーです。クォートされていないベクター節も受け付けますが、本物の Clojure はそれを拒否します。プレフィックスリスト `'(prefix [sub ...])` は各メンバーをプレフィックスの下に配線します（クォートの有無は問いません）。

```clojure
(use '[clojure.string :only [upper-case]])
(println (upper-case "hi")) ; HI
```
