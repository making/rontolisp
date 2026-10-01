# alter

`(alter r f args...)`

`dosync` の内側で、ref の古い値と引数に関数 `f` を適用し、ref の validator を通して書き込み、新しい値を返します。`swap!` 同様、関数値として動きます。

```clojure
(def r (ref 0))
(def bump alter)
(dosync (bump r + 40 2))
(println @r) ; 42
```
