# read-line

`(read-line)`

`*in*` の次の 1 行を行末を除いて読み、末尾を越えるとオラクル同様 `nil` を返します。
値としては引数をとらない関数です。

```clojure
(println (with-in-str "p\nq" (doall (repeatedly 3 read-line)))) ; (p q nil)
```
