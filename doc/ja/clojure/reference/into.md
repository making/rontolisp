# into

`(into to from)` / `(into to xform from)`

`from` を `to` へ1要素ずつ conj した結果を返します。リストは先頭、ベクターは末尾に伸び、
マップはエントリ、セットは要素を取ります。[トランスデューサー](transducers.md) `xform` を
渡すと、`from` を先にそれへ通します（オラクルの `(transduce xform conj to from)`）。lazy な
`from` も最後まで流し込みます。値としては2引数か3引数を取ります。

```clojure
(println (into [] [1 2])) ; [1 2]
(println (into {} [[:a 1]])) ; {:a 1}
(println (into '() [1 2])) ; (2 1)
(println (into [] (map inc) [1 2])) ; [2 3]
```
