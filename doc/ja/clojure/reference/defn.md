# defn

`(defn name docstring? [params...] body...)`
`(defn name docstring? ([params...] body...)+`

関数を定義します。多アリティの綴りはアリティごとに 1 節と、引数個数によるディスパッチです。単一の可変長節（`&` rest）は固定引数を超える任意の個数を受け、それ以外の個数はシグナルを上げます（`wrong number of arguments passed to: f`）。可変長節は高々 1 つ、アリティごとの節は 1 つです。引数はベクターもマップパターンも destructuring できます。名前は直接呼び出しへ低レベル化されるため、再帰は値参照ではなく呼び出しです。本体中の `recur` は囲みの節へ新しい引数値で跳び戻ります。head 位置での*値*束縛（`def`、引数）の使用は代わりに funcall になります。

定義は、下で定義される名前を使えます。ファイルはすべてのトップレベル `def`/`defn`（と `declare`）の名前で事前走査されます。本体の中では `defn` は文位置でのみ動き、多アリティのものはトップレベルでのみ動きます。

```clojure
(defn fact [n]
  (if (< n 2) 1 (* n (fact (- n 1)))))
(println (fact 5)) ; 120

(defn area
  ([] 0)
  ([w h] (* w h)))
(println (area 3 4)) ; 12

(defn resty [x & xs] xs)
(println (resty 1 2 3)) ; (2 3)
```
