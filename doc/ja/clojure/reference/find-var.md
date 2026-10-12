# find-var

`(find-var sym)`

名前空間付きシンボルが指す var を返します。その名前空間にない var なら `nil` です。オラクルの
`Var.find` と同じく、名前空間の部分はエイリアスではなく名前空間そのものの名前で、存在しない
名前空間はオラクルと同じ `IllegalArgumentException`（`No such namespace: x`）、名前空間のない
シンボルも同じ例外（`Symbol must be namespace-qualified`）です。クォートしたシンボルは
プログラムの変換時に解決し、計算したシンボルはマクロ本体の中でだけ解決します（hiccup の `html`
はこの方法で動的 var を見つけます）。実行時の計算したシンボルは `UnsupportedOperationException`
を送出します。関数値としても使えます。

```clojure
(def ^:dynamic *depth* 0)
(println (find-var 'user/*depth*)) ; #'user/*depth*
(println (find-var 'user/nowhere)) ; nil
(println (find-var 'clojure.string/join)) ; #'clojure.string/join
```
