# new

`(new Class args...)`

ホストオブジェクトを構築します。`(Class. args)` の接尾辞の綴りは同じ操作です。インスタンスはほかのどの interop 動詞にも応えます。インタープリターと JVM でのみ動作し、wasm バックエンドは `java:` を拒否します。次の3つの構築は、どのバックエンドでもホストオブジェクトではなくストリームになります。引数なしの `java.io.StringWriter`（[with-out-str](with-out-str.md) 参照）、ストリーム（`clojure.java.io/reader`、`*in*`）の上の `java.io.PushbackReader` と `java.io.BufferedReader`（そのストリーム自身）、`java.io.StringReader` の構築の上のそれら（文字列のリーダ）です。後の2つは [read](read.md) が読むものです。

```clojure
(println (.length (new String "hi"))) ; 2
```
