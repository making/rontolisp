# with-loading-context

`(with-loading-context body...)`

本体をそのまま実行し、最後の値を返します。ここではロード用の別コンテキストは通しません。oracle は本体の周囲でコンパイラのローダーを束縛し直しますが、ロードを過ぎれば観測できません。

```clojure
(println (with-loading-context (+ 1 2))) ; 3
```
