# get-method

`(get-method multifn dispatch-value)`

`dispatch-value` にディスパッチする呼び出しが実行するメソッドを返します。その値の下に格納されたメソッド、なければ multimethod の階層で祖先が持つメソッド、なければ既定値のメソッド、それもなければ `nil` です。メソッドは first-class の関数値で、multimethod の引数で呼び出せます。

```clojure
(defmulti g :k)
(defmethod g :x [m] 1)
(println ((get-method g :x) {:k :x})) ; 1
(println (get-method g :zzz)) ; nil
(defmethod g :default [m] 0)
(println ((get-method g :zzz) {})) ; 0
```
