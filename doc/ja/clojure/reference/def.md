# def

`(def name)` `(def name expr)`

トップレベル変数を定義し、mangle した名前への `setq` へ低レベル化します（値がなければ `nil`）。本体の中では、`def` は本体の実行時にグローバルへ設定します -- ローカル束縛ではなく、ローカル束縛は `let` の役割です。名前は `VARIABLE` なので、head 位置での呼び出しは値セルの `funcall` です。

値は古い束縛に対して評価されるため、`(defn p ...)` の後の `(def p (memoize p))` は関数セルを取り込みます。トップレベル呼び出しはキャッシュに当たり、`defun` 自身の再帰は直接呼び出しのままです -- 完全にメモ化された再帰には `^:dynamic` を使います（[defn](defn.md) 参照）。

```clojure
(def dv 42)
(println dv)        ; 42
(def dv-no-val)
(println dv-no-val) ; nil

(defn dv-f [x] (* x 2))
(def dv-f (memoize dv-f))
(println [(dv-f 21) (dv-f 21)]) ; [42 42]
```
