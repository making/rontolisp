# iterator-seq

`(iterator-seq iter)`

`java.util.Iterator` が辿る要素の seq を返します。要素がなければ `nil` です。`hasNext` と
`next` を通して 1 要素ずつ realize します（オラクルは 32 要素ずつ）。イテレータには、
`java.util.Iterator` を実装した `reify` や `deftype`
（[コレクションのインタフェース](reify.md#collection-interfaces)）、コアのコレクションや
`Iterable` な型の `.iterator`、ホストのイテレータ（インタプリタと JVM）を渡せます。

```clojure
(println (iterator-seq (.iterator [1 2 3]))) ; (1 2 3)
(deftype Countdown [^:unsynchronized-mutable n]
  java.util.Iterator
  (hasNext [_] (pos? n))
  (next [_] (let [v n] (set! n (dec n)) v)))
(println (iterator-seq (Countdown. 3))) ; (3 2 1)
(println (iterator-seq (.iterator []))) ; nil
```
