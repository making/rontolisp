# delay?

`(delay? x)`

`clojure.core/delay?`: [delay](delay.md) には `true`、それ以外の値には `false` を返します。引数は評価されます。値としては1引数の関数です。

```clojure
(println (delay? 1) (delay? (delay 1)))  ; false true
```
