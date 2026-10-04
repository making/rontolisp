# parents

`(parents tag)`
`(parents h tag)`

階層 -- グローバルのもの、2 引数形式では `h` -- における `tag` の直接の親のセットを返します。親がなければ nil です。クラス名は `class` がそれに答えるキーワードで（`isa?` を参照）、クラスは Java の基底、つまりスーパークラスと実装するインタフェースを加えます。ホストのクラスオブジェクト（インタプリタと JVM）はオラクルと同じくそれらをクラスオブジェクトとして加えます。

```clojure
(derive :c :p)
(println (parents :c)) ; #{:p}
(println (parents :x)) ; nil
(println (parents NumberFormatException)) ; #{:java.lang.IllegalArgumentException}
```
