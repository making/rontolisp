# isa?

`(isa? child parent)`
`(isa? h child parent)`
`(isa? vec1 vec2)`

`child` が `parent` と関係するかどうかを返します。等価、要素ごとのベクター派生（各要素が対応するものへ `isa?`）、または階層 -- グローバルのもの、3 引数形式では `h` -- を通る祖先 membership のいずれかです。`true` か `false` を返し、multimethod のディスパッチ検索はこれの上に組み立てられています。

`child` や `parent` に書いたクラス名は、`defmethod` と同じく `class` がその値に答える
キーワードです。`String` は `:string`、record はそのタグ、throwable やストリームのクラスは
その名前です。throwable やストリームのクラスのキーワードは、オラクルの Java の継承と同じく
各スーパークラスとそれらの派生先に `isa?` です（インタフェースと `Object` は除きます）。

```clojure
(derive :c :p)
(println (isa? :c :p)) ; true
(println (isa? :c :c)) ; true
(println (isa? :p :c)) ; false
(println (isa? (class "a") String)) ; true
(println (isa? (class (NumberFormatException. "x")) IllegalArgumentException)) ; true
```
