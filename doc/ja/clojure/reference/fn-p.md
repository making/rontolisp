# fn?

`(fn? x)`

`x` が関数かどうかを `T` か false で返します。キーワード、集合、マップは、呼び出せる
場合でも関数ではありません。

```clojure
(defn twice [x] (* 2 x))
(println (fn? twice) (fn? #(+ % 1)) (fn? :k) (fn? #{1}))
```

```
true true false false
```
