# deliver

`(deliver p v)`

`clojure.core/deliver`: promise `p` に `v` を配達し、その promise を答えます。2 回めの配達は最初の値を保ちます（オラクルと同じ）。promise でないものはオラクルの cast 失敗です（`nil` は `NullPointerException`）。値としては 2 引数の関数です。

```clojure
(let [p (promise)] (deliver p 1) (deliver p 2) (println @p)) ; 1
```
