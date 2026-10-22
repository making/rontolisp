# loop

`(loop [binding...] body...)`

`let` のように束縛し -- 初期化は逐次で、パターンは destructuring します -- 本体を評価します。本体は新しい値とともに束縛へ `(recur ...)` して戻れます。`labels` の自己呼び出しへ低レベル化されるため、インタープリターの末尾呼び出しにより繰り返しはスタック定数になります。

```clojure
(println (loop [a 0 b 1 i 0]
           (if (= i 10) a (recur b (+ a b) (inc i))))) ; 55
(println (loop [i 0] (if (= i 3) i (recur (inc i)))))  ; 3
```

本体が作るクロージャは、その反復の値を捉えます（オラクルと同様）-- 各 `recur` は新しい引数での新しい呼び出しです:

```clojure
(println (let [fns (atom [])] (loop [i 0] (when (< i 3) (swap! fns conj (fn [] i)) (recur (inc i)))) (mapv (fn [f] (f)) @fns))) ; [0 1 2]
```
