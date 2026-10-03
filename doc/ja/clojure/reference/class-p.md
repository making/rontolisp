# class?

`(class? x)`

`clojure.core/class?`: ホストのクラスオブジェクト（値としての `String` や `java.io.File`）なら `true` を返します。これは interop でしか得られません（インタプリタと JVM）。ここでは Lisp の値に対する `class` は種類のキーワードを返すため、`(class? (class 1))` は `false` です（オラクルは `true`）。値としては1引数の関数です。

```clojure
(println (class? 1) (class? (class 1)))  ; false false
```
