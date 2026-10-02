# reduced

`(reduced x)`

`x` を包み、畳み込みを止めてその値を返させます。`reduce`・`reduce-kv`・`transduce`・`into` と
逐次消費する側がこれを外し、`@` で値を読み戻せます。値としては1引数の関数です。

仕様との差異: reduced 値はラッパーのリストとして表示され、オラクルはオブジェクトを表示します。

```clojure
(println (reduce (fn [a x] (if (> x 2) (reduced a) (+ a x))) 0 [1 2 3 4])) ; 3
(println @(reduced 5)) ; 5
```
