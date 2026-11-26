# if

`(if test then else?)`

`test` を評価し、真なら `then`、そうでなければ `else`（省略時は `nil`）を返します。`nil` と `false` はどちらも偽値で、それ以外はすべて真値です -- テストは明示的な null-or-false チェックで、1 回だけ一時変数へ束縛されます。

```clojure
(println (if (< 1 2) :yes :no)) ; yes
(println (if false :yes))       ; nil
(println (if 0 :zero :empty))   ; zero
```
