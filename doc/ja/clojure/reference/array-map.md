# array-map

`(array-map k v ...)`

キー/値のペアからマップを組み立てて返します。`hash-map` と完全に同じで、`equal` ハッシュ表・コピーオンライト・走査順は未規定です。奇数個のペアは拒否されます。

Deviation: ここでは `array-map` と `hash-map` は同一で、どちらも同じ `equal` 表を組み立てます。oracle は `array-map` で挿入順を保持します。

```clojure
(println (get (array-map :a 1) :a)) ; 1
(println (count (array-map)))       ; 0
```
