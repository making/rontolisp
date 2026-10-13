# ns-unmap

`(ns-unmap ns sym)`

名前空間 `ns` の `sym` の対応（intern、refer、参照する core の var）を下ろしながら忘れる。`nil` を返す。oracle と同じ。求まる形でないものは実行時に拒否される。値としては 2 引数を取る。

```clojure
(refer 'clojure.string :only '[join])
(ns-unmap 'user 'join)
(println (get (ns-refers 'user) 'join)) ; nil
```
