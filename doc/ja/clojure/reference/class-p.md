# class?

`(class? x)`

`clojure.core/class?`: クラス値（[class](class.md) と [type](type.md) の答え、値としてのクラスリテラル（`String`・`java.io.File`・`clojure.lang.IFn`）、record・deftype のクラス）なら `true` を返します。オラクルの `(instance? Class x)` と同じです。値としては1引数の関数です。

```clojure
(defrecord R [a])
(println (class? 1) (class? (class 1)) (class? R) (class? (class (->R 1))))  ; false true true true
```
