# reify

`(reify Protocol (method [target & args] body...) ...)`

評価ごとに新しいディスパッチ値を答えます。各プロトコルの表に行を持ちます。
使い切りのマップにメソッドを添えたものであり、`proxy` ではありません（`proxy`
は `java:` サーフェスのまま）。各インスタンスは独自のタグでディスパッチする
ため、2つのインスタンスが `=` になることは決してありません（オラクル同様）。
それ以外の `=` は同一性です。メソッド群は `extend-type` 同様プロトコル名の下に
置きます。メソッド名を別のパラメータベクターで書き直すと、そのメソッドの別のアリティを
実装します。

```clojure
(defprotocol P (m [x]))
(def a (reify P (m [_] :a)))
(def b (reify P (m [_] :b)))
(println (m a))       ; :a
(println (m b))       ; :b
(println (= a a))     ; true
(println (= a b))     ; false
(println (satisfies? P a)) ; true
```

`clojure.core.protocols/CollReduce` の `reify` は、`reduce`・`into`・`transduce` がその
`coll-reduce` を通して畳み込むコレクションになります（[clojure.core.reducers](clojure-core-reducers.md)
のレデューサーはこの形で作られています）。

```clojure
(require '[clojure.core.protocols :as p])
(def three (reify p/CollReduce
             (coll-reduce [this f] (p/coll-reduce this f (f)))
             (coll-reduce [_ f init] (reduce f init [1 2 3]))))
(reduce + three) ; => 6
(into [] (map inc) three) ; => [2 3 4]
```
