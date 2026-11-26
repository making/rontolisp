# tagged-literal?

`(tagged-literal? x)`

`clojure.core/tagged-literal?`: `x` が [tagged-literal](tagged-literal.md) かどうかを返します。これを作るのは `{:read-cond :preserve}` の下でリーダ条件の中にあるタグとコンストラクタだけです。値としては1引数の関数です。

```clojure
(println (tagged-literal? (tagged-literal 'js {})))  ; true
(println (tagged-literal? 1))  ; false
```
