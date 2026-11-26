# first

`(first coll)`

`coll` の seq ビューの最初の要素を返します。ビューが空なら `nil` です。マップはエントリごとに 1 つの 2 要素ベクターを与えるため、マップの `first` はエントリのベクターです。値としては seq ビュー上のラムダなので、`map`/`filter` を裸のまま渡ります。

```clojure
(println (first [1 2]))   ; 1
(println (first {:a 1}))  ; [:a 1]
(println (first "ab"))    ; a
(println (first nil))     ; nil
```
