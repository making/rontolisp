# ifn?

`(ifn? x)`

`clojure.core/ifn?`: オラクルが呼び出せる値なら `true` を返します。関数・キーワード・シンボル・マップ・セット・ベクター・var が該当します。レコード・リスト・文字列・数値は `false` です。値としては1引数の関数です。

```clojure
(println (ifn? inc) (ifn? :a) (ifn? {}) (ifn? '(1)) (ifn? 1))  ; true true true false false
```
