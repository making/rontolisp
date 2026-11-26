# clojure.string/triml

`(clojure.string/triml s)`

左側だけの空白を削った `s` を返します。

```clojure
(println (str "[" (clojure.string/triml "  hi  ") "]")) ; [hi  ]
```
