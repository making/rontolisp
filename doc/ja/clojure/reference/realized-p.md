# realized?

`(realized? x)`

`clojure.core/realized?`: lazy seq `x` が本体を実行済みかを返します。リスト（strict な入力に対して操作が返す値）は実体化済みとして `true` を返し、オラクルの未実体化の lazy seq が `false` になる場面と異なります。`iterate`・`cycle`・`repeat` の seq は一度強制されてから実体化済みになります（オラクルでは最初から）。それ以外の値はオラクルと同様にシグナルを上げます。値としては1引数の関数です。

```clojure
(let [s (lazy-seq [1 2])]
  (println (realized? s) (first s) (realized? s)))  ; false 1 true
```
