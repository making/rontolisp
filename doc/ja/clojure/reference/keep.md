# keep

`(keep f coll)` / `(keep f)`

`coll` の seq ビューに対する `f` の結果のうち `nil` でないものを順に返します。`false`
は残り、`nil` だけが落ちます。要素でシグナルする関数はそのままシグナルします（オラクル同様
`(keep inc [1 nil 2])` は例外になります）。値としては2引数のラムダです。

`(keep f)` は[トランスデューサー](transducers.md)を返します（値としても同じです）。

```clojure
(println (keep inc [1 2 3])) ; (2 3 4)
(println (keep :k [{:k 1} {}])) ; (1)
(println (into [] (keep :k) [{:k 1} {}])) ; [1]
```
