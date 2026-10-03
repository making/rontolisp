# integer?

`(integer? x)`

`clojure.core/integer?`: 大きさによらず整数なら `true` を返します。ここでは `1M` リテラルは整数 `1` として読まれるため `true` になります（オラクルでは decimal なので `false`）。値としては1引数の関数です。

```clojure
(println (integer? 1) (integer? 99999999999999999999) (integer? 1.0))  ; true true false
```
