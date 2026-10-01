# letfn

`(letfn [(f [params...] body...)+] body...)`

相互再帰するローカル関数を束縛し、本体を評価します。本体はそれらを呼べます。すべての名前は
すべての項目から見えます -- 兄弟同士が互いを呼びます -- 本体からも見え、項目の名前は関数値
としても使えます。各項目は名前付き `fn` の節と同様に低レベル化されます（引数は同じく
destructuring でき、複数アリティは同じくディスパッチします）。そのため項目は自分自身へ
`recur` でき、本体は各項目を直接呼びます。空の束縛ベクターは単なる本体です。

```clojure
(println (letfn [(fact [x] (if (zero? x) 1 (* x (fact (dec x)))))] (fact 5))) ; 120
(println (letfn [(even? [n] (if (zero? n) true (odd? (dec n))))
                 (odd? [n] (if (zero? n) false (even? (dec n)))))]
           (even? 10))) ; true
(let [y 7]
  (println (letfn [(g [x] (+ x y))] (g 3)))) ; 10
```
