# ns-refers

`(ns-refers ns)`

名前空間 `ns`（シンボル、文字列、`*ns*`）への refer。各名前とその var のマップ。oracle と同じ。下ろしながら分かるものだけが載り、求まる形でないものは実行時に拒否される。値としては 1 引数を取る。

```clojure
(refer 'clojure.string :only '[join])
(println (some? (get (ns-refers 'user) 'join))) ; true
```
