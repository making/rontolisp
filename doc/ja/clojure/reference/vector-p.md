# vector?

`(vector? x)`

`x` がベクターかどうかを返します。`T` か false です。リストはベクターではありません。

```clojure
(println (vector? [1]))  ; true
(println (vector? '(1))) ; false
(println (vector? "ab")) ; false
```
