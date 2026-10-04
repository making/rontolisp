# ancestors

`(ancestors tag)`
`(ancestors h tag)`

階層 -- グローバルのもの、2 引数形式では `h` -- における `tag` のすべての祖先を、推移的に含むセットを返します。祖先がなければ nil です。クラス名は `class` がそれに答えるキーワードで（`isa?` を参照）、クラスはインタフェースと `Object` を含む Java のスーパータイプと、それらの階層上の祖先を加えます。ホストのクラスオブジェクト（インタプリタと JVM）はオラクルと同じくスーパータイプをクラスオブジェクトとして加えます。

```clojure
(derive :c :p)
(derive :p :q)
(println (ancestors :c)) ; #{:p :q}
(println (count (ancestors Exception))) ; 3
```
