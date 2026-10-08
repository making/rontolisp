# reader-conditional?

`(reader-conditional? x)`

`clojure.core/reader-conditional?`: どの値にも `false` を返します。リーダー条件式は選ばれた分岐として読まれ、それを値として組む `{:read-cond :preserve}` は拒否されるため、リーダー条件式の値はありません。引数は評価されます。値としては1引数の関数です。

```clojure
(println (reader-conditional? '(1)))  ; false
```
