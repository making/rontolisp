# alter-meta!

`(alter-meta! ref f & args)`

参照 `ref`（var、アトム、ref、エージェント、名前空間）のメタデータを `(apply f meta args)` に
置き換え、それを返します。var は、その名前の後の定義が置き換えるまで新しいメタデータを
保ちます（オラクルの `def` と同じです）。それ以外の値（volatile、コレクション）とマップでない
結果は `ClassCastException`、`nil` は `NullPointerException` です。値としては2引数以上の関数です。

```clojure
(defn add "Adds two numbers." [a b] (+ a b))
(def plus add)
(alter-meta! #'plus assoc :doc (:doc (meta #'add)))
(println (:doc (meta #'plus))) ; Adds two numbers.
```
