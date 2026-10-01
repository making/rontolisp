# defmethod

`(defmethod name dispatch-value [params...] body...)`

メソッドのラムダを multimethod の表へ `dispatch-value` の下に格納します。引数は destructuring し、マップパターンも含まれます。呼び出しでは、ディスパッチ値への完全一致が直接適用されます。そうでなければディスパッチャは、multimethod の階層を通ってその値が降りてくるすべてのメソッドを探します。狭義に最も具体的なものが勝ち、残った同順は `prefer-method` が決め、決着しない同順は `Multiple methods ...` をシグナルします。

ディスパッチ値は `equal` 表のキーと同じく比較されます -- ベクターは同一性なので、ベクターのディスパッチ値は自分自身にしか当たりません。

Deviation: ベクターのディスパッチ値は構造的ではなく同一性で比較されます。

```clojure
(defmulti m :shape)
(defmethod m :circle [x] 1)
(defmethod m :square [x] 2)
(println (m {:shape :circle})) ; 1
```
