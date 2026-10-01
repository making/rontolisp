# clojure.string/trimr

`(clojure.string/trimr s)`

右側だけの空白を削った `s` を返します。

```clojure
(println (str "[" (clojure.string/trimr "  hi  ") "]")) ; [  hi]
```
