# unreduced

`(unreduced x)`

`x` が [`reduced`](reduced.md) 値ならその中身を、そうでなければ `x` 自身を返します。値としては
1引数の関数です。

```clojure
(println (unreduced (reduced 2)) (unreduced 3)) ; 2 3
```
