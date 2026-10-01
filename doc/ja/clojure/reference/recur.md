# recur

`(recur expr...)`

囲んでいる `loop` の束縛を与えられた値へ向け直し、その本体へ跳び戻ります。値は跳ぶ前に、束縛の順に評価されます。`loop` の中でのみ動きます -- 低レベル化が `loop` の `labels` 自己呼び出しのため、関数位置の `recur` はありません。インタープリターの末尾呼び出しによりスタック定数です。

```clojure
(println (loop [i 0 acc 0]
           (if (= i 5) acc (recur (inc i) (+ acc i))))) ; 10
```
