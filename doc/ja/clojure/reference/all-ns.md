# all-ns

`(all-ns)`

プログラムがその呼び出しまでに作った名前空間（自分の、require した、oracle が先に読むもの）を名前空間オブジェクトで。消したものは除く。oracle と同じ。順序は不定。引数なしの値。

```clojure
(println (contains? (set (map str (all-ns))) "clojure.core")) ; true
```
