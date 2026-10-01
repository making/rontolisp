# array-map

`(array-map k v ...)`

キー/値のペアからマップを組み立てて返します。`hash-map` と完全に同じで、`equal` ハッシュ表・コピーオンライト・走査順は未規定です。奇数個のペアは拒否されます。

値としてはペアの残り引数列を取るラムダです。残り引数が奇数個の場合は実行時にシグナルを上げます。

仕様との差異: ここでは `array-map` と `hash-map` は同一で、どちらも同じ `equal` 表を組み立てます。オラクルは `array-map` で挿入順を保持します。

```clojure
(println (get (array-map :a 1) :a)) ; 1
(println (count (array-map)))       ; 0
(println (get ((fn [f] (f :a 1)) array-map) :a)) ; 1
```
