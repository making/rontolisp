# if-not

`(if-not test then)` / `(if-not test then else)`

テストが truthy でなければ `then`、そうでなければ else 分岐で答えます（else なしでは
`nil`）。

```clojure
(println (if-not nil :t :e)) ; :t
(println (if-not 1 :t :e)) ; :e
```
