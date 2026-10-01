# get-method

`(get-method multifn dispatch-value)`

`dispatch-value` の下に格納されたメソッドのラムダを返し、なければ `nil` を返します。ラムダは first-class の関数値で、multimethod の引数で呼び出せます。

```clojure
(defmulti g :k)
(defmethod g :x [m] 1)
(println ((get-method g :x) {:k :x})) ; 1
(println (get-method g :zzz)) ; nil
```
