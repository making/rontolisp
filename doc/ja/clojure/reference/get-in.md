# get-in

`(get-in m keys)` / `(get-in m keys dflt)`

キーベクターを下る読み取りを返します。デフォルトは全階層に渡るため（途中の欠けもデフォルトに
なります）、オラクル同様です。値としては実行時にキー列をたどります。

```clojure
(println (get-in {:a {:b 1}} [:a :b])) ; 1
(println (get-in {} [:a :b] :dflt)) ; :dflt
```
