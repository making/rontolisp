# unchecked-remainder-int

`(unchecked-remainder-int a b)`

引数を `unchecked-add-int` と同様にintに変換し、余り（符号は `a` と同じ）を返します。除数が0のときはシグナルします。値としては2引数の関数です。

```clojure
(println (unchecked-remainder-int -7 2)) ; -1
```
