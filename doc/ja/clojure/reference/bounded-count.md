# bounded-count

`(bounded-count n coll)`

数えられるコレクションは `n` に関わらず件数、それ以外は `n` 件まで歩いた件数。終わりのない seq の歩きは `n` で答える。oracle と同じ。lazy-or-strict 動詞が strict に実現した seq は件数全部になるところだけが違う（oracle では lazy のまま `n` で止まる）。値としては 2 引数を取る。

```clojure
(println (bounded-count 5 [1 2 3]) (bounded-count 2 [1 2 3])) ; 3 3
```
