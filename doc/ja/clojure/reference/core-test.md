# test

`(test v)`

`clojure.core/test` です。var のメタデータの `:test` にある引数なしの関数を呼び、`:ok` を
返します。なければ `:no-test` です。関数が投げたものはそのまま通るため、失敗した
`assert` は `AssertionError` として呼び出し元に届きます。

```clojure
(defn ^{:test (fn [] (assert (= 4 (sq 2))))} sq [x] (* x x))
(println (test #'sq))   ; :ok
(defn plain [] 1)
(println (test #'plain)) ; :no-test
```
