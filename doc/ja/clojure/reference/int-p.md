# int?

`(int? x)`

`clojure.core/int?`: long に収まる整数（オラクルの `Long`・`Integer`・`Short`・`Byte`）なら `true`、それより大きい整数は `false` を返します。ここでは `2N` リテラルは整数 `2` として読まれるため `true` になります（オラクルでは `BigInt` なので `false`）。値としては1引数の関数です。

```clojure
(println (int? 1) (int? 9223372036854775808))  ; true false
```
