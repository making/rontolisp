# ratio?

`(ratio? x)`

`clojure.core/ratio?`: 分数（整数でない有理数）なら `true` を返します。ここでは `1.5M` は `3/2` として読まれるため `true` になります（オラクルでは `false`）。値としては1引数の関数です。

```clojure
(println (ratio? 1/2) (ratio? 2) (ratio? 0.5))  ; true false false
```
