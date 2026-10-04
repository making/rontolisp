# instance?

`(instance? Class x)`

`x` がそのクラスのインスタンスのとき `true` です（オラクルの `isInstance` と同じ）。クラスや
インタフェースは、オラクルでのクラスがそれ自身であるか、それを実装する種類の値すべてについて
答えます。`java.util.List` はベクタ・リスト・遅延 seq、`java.util.Map` はマップと record、
`clojure.lang.IFn` は関数・キーワード・シンボル・マップ・セット・ベクタ・var、`Comparable` は
文字列・数値・キーワード・ベクタ、`java.io.Writer` は `*out*` について `true` です。`Object` は
`nil` 以外のすべての値です。既知の record/deftype 名はディスパッチタグの検査になります。
throwable クラス（`Exception`、`IllegalArgumentException`、`clojure.lang.ExceptionInfo`、
ドット付きや import した名前）は、例外と実行時エラーをそのクラス（`class` が返すクラス）か
そのサブクラスであるかで検査します。インタプリタと JVM ではホストのオブジェクトをホストの
クラスで検査するので、`(instance? java.io.File (java.io.File. "x"))` と
`(instance? Number (java.math.BigDecimal. "1"))` は `true` です。ここではどの値もインスタンスに
ならないクラス（Clojure の値に対する `java.io.File`、`Integer`）は `false`、どのクラスでもない
名前は `unknown name` になります。

```clojure
(println (instance? String "a") (instance? String 1)) ; true false
(println (instance? java.util.List [1]) (instance? clojure.lang.IFn :k) (instance? java.util.Map [1])) ; true true false
(println (instance? RuntimeException (IllegalArgumentException. "x")) (instance? RuntimeException (Exception. "x"))) ; true false
```
