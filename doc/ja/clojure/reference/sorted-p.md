# sorted?

`(sorted? x)`

`clojure.core/sorted?`: どの値にも `false` を返します。ソート済みコレクションはまだないため、ここにはそれに当たる値がありません。引数は評価されます。値としては1引数の関数です。

```clojure
(println (sorted? {:a 1}) (sorted? [1 2]))  ; false false
```
