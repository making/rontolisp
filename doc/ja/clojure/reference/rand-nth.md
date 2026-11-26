# rand-nth

`(rand-nth coll)`

プログラム所有の生成器による1回のスケール済み抽選で1要素を選びます。`nil` には
`nil` で答えます。空のベクター・文字列・seq は oracle と同様にシグナルします。
マップとセットもシグナルします（どちらもインデックスされません）。所属だけが固定され、
値が固定されることはありません。

```clojure
(println (rand-nth [:only])) ; :only
(println (contains? #{:a :b} (rand-nth [:a :b]))) ; true
```
