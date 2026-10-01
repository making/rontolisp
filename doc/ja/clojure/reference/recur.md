# recur

`(recur expr...)`

最も内側の囲み `loop`、名前付き・無名の `fn`、`defn`、`letfn` 項目を与えられた値へ向け直し、
その本体へ跳び戻ります。値は跳ぶ前に、束縛の順に評価されます。多アリティの各節はそれぞれ
対象なので、個数は囲みの節に一致しなければなりません。個数の不一致はエラーで、`loop`
にも関数にも囲まれない `recur` もエラーです。可変長（`[x & xs]`）節へ届く `recur` は拒否
されます。oracle は rest 引数に最後の引数そのものを束縛しますが、`&rest` 自己呼び出しに
その綴りはありません。インタープリターの末尾呼び出しによりスタック定数です。

```clojure
(println (loop [i 0 acc 0]
           (if (= i 5) acc (recur (inc i) (+ acc i))))) ; 10
(println ((fn countdown [n acc] (if (zero? n) acc (recur (dec n) (+ acc n)))) 5 0)) ; 15
(defn greet-again [n]
  (if (zero? n) :done (recur (dec n))))
(println (greet-again 3)) ; :done
```
