# ns-unalias

`(ns-unalias ns alias-sym)`

名前空間 `ns` の別名 `alias-sym` を下ろしながら忘れる。`nil` を返す。oracle と同じ。求まる形でないものは実行時に拒否される。値としては 2 引数を取る。

```clojure
(alias 'doc-eg-s3 'clojure.string)
(ns-unalias 'user 'doc-eg-s3)
(println (get (ns-aliases 'user) 'doc-eg-s3)) ; nil
```
