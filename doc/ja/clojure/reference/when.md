# when

`(when test body...)`

`test` を評価し、真なら本体のフォームを評価して最後の値を返します。そうでなければ本体に触れず `nil` を返します。`nil` と `false` はどちらも偽値で、ここのすべてのテストと同様です。

```clojure
(println (when true 1 2))  ; 2
(println (when nil 1 2))   ; nil
(println (when false 1))   ; nil
```
