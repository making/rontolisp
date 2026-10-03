# hash-set

`(hash-set x ...)`

`clojure.core/hash-set`: 引数のセットを返します。重複した引数は1つにまとまります。`#{...}`
リテラルと同じ `equal` ハッシュ表で、走査順は未規定です。値としては任意個の引数を取る
関数です。

```clojure
(prn (count (hash-set 1 2 1))) ; 2
(prn (hash-set))               ; #{}
```
