# printf

`(printf fmt & args)`

`fmt` と `args` の `format` を `*out*` に書く。`nil` を返す。oracle と同じ。値としては 1 引数以上を取る。

```clojure
(println (with-out-str (printf "%d %s" 42 "hi"))) ; 42 hi
```
