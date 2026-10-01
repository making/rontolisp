# use

`(use clause)`

namespace をロードし、節が列挙する名前を refer して、`nil` を返します -- `(:only [...])` が名前を狭め、`ns` が行うのと同じ refer の配線です。解決するのは `clojure.string` のみで、未知の namespace はエラーです。

```clojure
(use [clojure.string :only [upper-case]])
(println (upper-case "hi")) ; HI
```
