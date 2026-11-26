# conj

`(conj coll x ...)`

値を加えた新しいコレクションを返します。種類ごとに、セットには要素、マップにはエントリ、ベクターと[キュー](persistent-queue.md)には末尾、リストには先頭に加えます -- `nil` はリストのように集めます。マップへ conj されるセットやシーケンス（遅延も可）は、その要素（2 要素のベクター）をエントリとして与え、ソート済みマップは各ペアを、Java の `Map`（インタプリタと JVM）は各エントリを与えます。エントリでない要素のリスト（`(k v)` を含む）はエントリではなく、それ以外のものと同じくマップへ conj するとシグナルを上げます。record へのエントリ追加は型を保ち、atom（同じセルである ref/agent/volatile を含む）や deftype/reify へはオラクル同様シグナルを上げます。

値としてはコレクションに残り引数の要素列を 1 つずつ畳み込みます。`alter`/`swap!` に `conj` を渡せます。引数なしでは `[]` を返します（`transduce` が呼ぶ初期値アリティです）。

[`conj!`](conj-bang.md) はトランジェントにその場で追加します。

```clojure
(println (conj [1 2] 3))  ; [1 2 3]
(println (conj '(1 2) 3)) ; (3 1 2)
(println (conj nil 1))    ; (1)
(println (get (conj {:a 1} [:b 2]) :b)) ; 2
(println (count (conj #{1 2} 3)))       ; 3
(println (map conj [[1] [2]] [3]))     ; ([1 3])
(println (conj)) ; []
```
