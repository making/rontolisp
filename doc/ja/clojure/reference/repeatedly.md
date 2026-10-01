# repeatedly

`(repeatedly f)` / `(repeatedly n f)`

`(f)` 呼び出しの無限 lazy seq、または（カウント付きで）`n` 回呼び出した strict リストを返します。0以下のカウントは `nil` です。有限アリティはオラクル通りに表示されます。無限の方は `take` 越しにだけ終了します。

```clojure
(println (take 3 (repeatedly (fn [] 7)))) ; (7 7 7)
(println (repeatedly 2 (fn [] 7)))        ; (7 7)
```
