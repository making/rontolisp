# recur

`(recur expr...)`

最も内側の囲み `loop`、名前付き・無名の `fn`、`defn`、`letfn` 項目を与えられた値へ向け直し、
その本体へ跳び戻ります。値は跳ぶ前に、束縛の順に評価されます。多アリティの各節はそれぞれ
対象なので、個数は囲みの節に一致しなければなりません。個数の不一致はエラーで、`loop`
にも関数にも囲まれない `recur` もエラーです。末尾位置にある `recur` だけが展開され、
それ以外はエラー、`recur` と対象のあいだに `try` がある場合もエラーです。可変長（`[x & xs]`）節へ届く `recur` はワーカーを経由して代入されます。その節は rest を通常引数に取るワーカーと、通常呼び出し用の `&rest` ヘッドに分割されるため、最後の `recur` 引数が rest そのものに束縛されます（oracle と同様。使われない可変長節は単一の形のままです）。`lazy-seq` の本体はそれ自体が0引数の対象です。本体の末尾位置にある `recur` は thunk 自体を再実行するため、引数付きの recur はそこで個数エラーになります。`deftype`・`defrecord`・`reify` のメソッドはそれ自身のアリティへ、対象を除くすべての引数で再帰します。対象はメソッド自身が与えます（oracle と同様）。`extend-protocol`・`extend-type` の本体は `fn` なので、その `recur` は対象も渡します。インタープリターの末尾呼び出しによりスタック定数です。

```clojure
(println (loop [i 0 acc 0]
           (if (= i 5) acc (recur (inc i) (+ acc i))))) ; 10
(println ((fn countdown [n acc] (if (zero? n) acc (recur (dec n) (+ acc n)))) 5 0)) ; 15
(defn greet-again [n]
  (if (zero? n) :done (recur (dec n))))
(println (greet-again 3)) ; :done
(defn walk [a & r]
  (if (empty? r) a (recur (first r) (rest r))))
(println (walk :start [1])) ; [1]
(println (walk :start nil)) ; nil
(defmulti walk-shape (fn [m & r] (:shape m)))
(defmethod walk-shape :go [m & r]
  (if (empty? r) (:v m) (recur {:v (first r)} (rest r))))
(println (walk-shape {:shape :go :v :start} [1])) ; [1]
(defprotocol Counter (count-up [c acc]))
(deftype Limit [n] Counter
  (count-up [this acc] (if (>= acc n) acc (recur (inc acc)))))
(println (count-up (Limit. 3) 0)) ; 3
(def calls (atom 0))
(def draining (lazy-seq (swap! calls inc) (when (< @calls 3) (recur))))
(println (first draining)) ; nil
(println @calls) ; 3
```
