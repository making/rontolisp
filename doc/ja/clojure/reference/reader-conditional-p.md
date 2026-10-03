# reader-conditional?

`(reader-conditional? x)`

`clojure.core/reader-conditional?`: どの値にも `false` を返します。リーダーが `#?` を拒否するため、リーダー条件式の値はありません。引数は評価されます。値としては1引数の関数です。

```clojure
(println (reader-conditional? '(1)))  ; false
```
