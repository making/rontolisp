# def

`(def name)` `(def name expr)`

トップレベル変数を定義し、mangle した名前への `setq` へ低レベル化します（値がなければ `nil`）。本体の中では、`def` は本体の実行時にグローバルへ設定します -- ローカル束縛ではなく、ローカル束縛は `let` の役割です。名前は `VARIABLE` なので、head 位置での呼び出しは値セルの `funcall` です。

```clojure
(def dv 42)
(println dv)        ; 42
(def dv-no-val)
(println dv-no-val) ; nil
```
