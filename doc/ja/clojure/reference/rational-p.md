# rational?

`(rational? x)`

`clojure.core/rational?`: 整数または分数なら `true` を返します（`M` リテラルもここでは正確な有理数で、オラクルと同じく `true`）。double は `false` です。値としては1引数の関数です。

```clojure
(println (rational? 1) (rational? 1/2) (rational? 0.5))  ; true true false
```
