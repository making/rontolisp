# keep

`(keep f coll)` / `(keep f)`

`coll` の seq ビューに対する `f` の結果のうち `nil` でないものを順に返します。`false`
は残り、`nil` だけが落ちます。要素でシグナルする関数はそのままシグナルします（オラクル同様
`(keep inc [1 nil 2])` は例外になります）。lazy な入力には lazy seq を、strict な入力には strict なリストを返します。値としては2引数のラムダです。

`(keep f)` は[トランスデューサー](transducers.md)を返します（値としても同じです）。

```clojure
(println (keep inc [1 2 3])) ; (2 3 4)
(println (keep :k [{:k 1} {}])) ; (1)
(println (take 2 (keep #(when (odd? %) %) (iterate inc 0)))) ; (1 3)
(println (into [] (keep :k) [{:k 1} {}])) ; [1]
```
