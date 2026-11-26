# class?

`(class? x)`

`clojure.core/class?`: ホストのクラスオブジェクト（値としての `String` や `java.io.File`）なら `true` を返します。これは interop でしか得られません（インタプリタと JVM）。ホストオブジェクトのないクラス値（record・deftype のクラス、[type](type.md) の答え、wasm バックエンドのクラスリテラル）にも `true` を返します。ここではほとんどの Lisp の値に対する `class` は種類のキーワードを返すため、`(class? (class 1))` は `false` です（オラクルは `true`）。値としては1引数の関数です。

```clojure
(defrecord R [a])
(println (class? 1) (class? (class 1)) (class? R) (class? (class (->R 1))))  ; false false true true
```
