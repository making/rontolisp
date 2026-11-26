# rsubseq

`(rsubseq sc test key)` / `(rsubseq sc start-test start-key end-test end-key)`

`clojure.core/rsubseq`: 逆向きに走査する `subseq` です。テストが `<` か `<=` なら `key` から
始め、それ以外なら末尾の要素から始めます。5引数の形は終端の境界から始め、始端の境界が
成り立つあいだ続けます。結果は strict なリストで、何も残らなければ `nil` です。値としては
3引数か5引数を取ります。

```clojure
(println (rsubseq (sorted-set 1 2 3 4) < 3))        ; (2 1)
(println (rsubseq (sorted-set 1 2 3 4) >= 2 <= 3))  ; (3 2)
```
