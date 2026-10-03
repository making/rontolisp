# assoc

`(assoc m k v ...)`

`m` のペアに与えたペアを加えた新しいマップを返します。後のペアが勝ちます。`m` は決して書き換えられません。`nil` への `assoc` は空から始めます。奇数個のペアは拒否されます。キーは `=` で比較し、既にある `=` なキーへの追加はそのキーを保ったまま値を置き換えます。record への追加はエントリ表に入り、型は保たれます。

値としてはマップに残り引数のペア列を取ります。残り引数が奇数個の場合は実行時にシグナルを上げます。

Transient（`assoc!`）は名前で拒否されます。

```clojure
(def mm-base {:a 1})
(println (get (assoc mm-base :b 2) :b)) ; 2
(println (get mm-base :b))              ; nil
(println (get (assoc nil :a 1) :a))     ; 1
(println (get ((fn [f] (f {:a 1} :b 2)) assoc) :b)) ; 2
(println (assoc {[1 2] :a} '(1 2) :b)) ; {[1 2] :b}
```
