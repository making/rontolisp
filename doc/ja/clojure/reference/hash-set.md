# hash-set

`(hash-set x ...)`

`clojure.core/hash-set`: 引数のセットを返します。重複した引数は1つにまとまります。`#{...}`
リテラルと同じ `equal` ハッシュ表で、メンバーは挿入順に印字され、`seq` も挿入順に
たどります（オラクルの hash set は独自の順です）。値としては任意個の引数を取る
関数です。

```clojure
(prn (count (hash-set 1 2 1))) ; 2
(prn (hash-set))               ; #{}
```
