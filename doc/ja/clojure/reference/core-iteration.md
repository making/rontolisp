# iteration

`(iteration step & {:keys [somef vf kf initk]})`

`clojure.core/iteration`: 継続トークンを取る関数 `step` を繰り返し呼ぶ、seq にも reduce にも
できる値を返します。最初の呼び出しは `initk`（既定は `nil`）を取って `ret` を返します。
`(somef ret)`（既定は `some?`）が真である間、`(vf ret)`（既定は `identity`）が次の要素、
`(kf ret)`（既定は `identity`）が次のトークンです。トークンが `nil` なら反復は終わります。
オプションはキーワード引数か 1 つのマップで渡します。

`seq`（と `first`、`map`、`take` など）は呼ぶたびに `initk` から `step` を呼び直し、lazy
です。ある要素の `somef`、`vf`、`kf` はその要素に達したときに、次の `step` は残りを realize
したときに走ります。init 付きの `reduce`、`into`、`vec`、`transduce`、`mapv` は直接 reduce
し、`reduced` で止まります。oracle と同じく、init なしの `reduce` は `ClassCastException`、
`count` は `UnsupportedOperationException` です。値としては 1 引数以上を取ります。

```clojure
(def pages (iteration (fn [k] (when (< k 3) {:page k :items [k k]}))
                      :initk 0 :kf (comp inc :page) :vf :items))
(println (vec pages))           ; [[0 0] [1 1] [2 2]]
(println (mapcat identity pages)) ; (0 0 1 1 2 2)
(println (take 3 (iteration inc :initk 0))) ; (1 2 3)
```
