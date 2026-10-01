# conj

`(conj coll x ...)`

値を加えた新しいコレクションを返します。種類ごとに、セットには要素、マップにはエントリ、ベクターには末尾、リストには先頭に加えます -- `nil` はリストのように集めます。マップへ conj されるセットは、その要素を 1 段深いところ（エントリとして）与えます。それ以外のものをマップへ conj するとシグナルを上げます。

仕様との差異: transient（`conj!`）は名前で拒否されます。

```clojure
(println (conj [1 2] 3))  ; [1 2 3]
(println (conj '(1 2) 3)) ; (3 1 2)
(println (conj nil 1))    ; (1)
(println (get (conj {:a 1} [:b 2]) :b)) ; 2
(println (count (conj #{1 2} 3)))       ; 3
```
