# bound-fn

`(bound-fn name? [params] body...)`
`(bound-fn* f)`

`bound-fn` は `fn` に対する `bound-fn*` です。fdecl はそのまま渡されるため、
`fn` の lowering が拒否する docstring やアリティはオラクルのマクロ展開が
失敗する箇所で失敗します。`bound-fn*` は関数を受け取り、生成時の束縛を
push して呼び出す関数を返します。生成時にスレッド束縛されているすべての
プログラムの `^:dynamic` var と core special に、字句的に内側にある
[`with-local-vars`](with-local-vars.md) のセルを加えたものが、呼び出し元の
束縛の上に積まれるため、別スレッドでの呼び出しも取り込んだ値を見ます。
生成時にどこにも束縛されていない var はオラクルのフレーム同様マップに
入りません。呼ばれた関数経由など動的に入った `with-local-vars`
の範囲下で作られた `bound-fn*` はそのセルを見ません。値としては2つとも
マクロのままです（`(def x bound-fn)` は拒否されます）。マクロの var 値は
後回しのため、そのピン留めもそちらに残ります。

```clojure
(def ^:dynamic *loud* false)
(defn status [] (if *loud* :loud :quiet))
(println (status)) ; :quiet
(binding [*loud* true]
  (def loud-status (bound-fn [] (status))))
(println (loud-status)) ; :loud
(println (status)) ; :quiet
```
