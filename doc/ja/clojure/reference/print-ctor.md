# print-ctor

`(print-ctor x writer-fn w)`

`x` のクラスの `#=` 形を、`writer-fn` が `x` について `w` に書いたもので囲んで書く。`nil` を返す。oracle と同じ。値としては 3 引数を取る。

```clojure
(println (with-out-str (print-ctor {:a 1} (fn [o w] (print-dup o w)) *out*))) ; #=(clojure.lang.PersistentArrayMap. #=(clojure.lang.PersistentArrayMap/create {:a 1}))
```
