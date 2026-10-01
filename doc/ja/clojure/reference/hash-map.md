# hash-map

`(hash-map k v ...)`

キー/値のペアから組み立てたマップを返します。`equal` ハッシュ表で、決して in place には書き換えず -- すべての動詞が新しい表を組み立てるため、永続性は観測可能な形で保たれます。走査順は未規定です。奇数個のペアは拒否されます。

値としてはペアの残り引数列を取るラムダです。残り引数が奇数個の場合は実行時にシグナルを上げます。

仕様との差異: ここでは `hash-map` と `array-map` は同一で、どちらも同じ `equal` 表を組み立てます。オラクルは `array-map` で挿入順を保持します。

```clojure
(println (get (hash-map :a 1 :b 2) :b)) ; 2
(println (count (hash-map)))            ; 0
(println (get ((fn [f] (f :a 1)) hash-map) :a)) ; 1
```
