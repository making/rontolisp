# tagged-literal?

`(tagged-literal? x)`

`clojure.core/tagged-literal?`: どの値にも `false` を返します。タグ付きリテラルの値はありません。引数は評価されます。値としては1引数の関数です。

```clojure
(println (tagged-literal? 1))  ; false
```
