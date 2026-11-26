# java:java-exception-cause

`(java:java-exception-cause condition)`

`java:java-exception` が保持する例外、つまり `java:` のメンバが投げた `java.lang.Throwable` を返します。`java:java-exception` はそのようなメンバが通知するコンディションで、report がメンバと例外を示す `simple-error` です。それ以外の値を渡すと `type-error` になります。JVM 専用の `java` 連携パッケージの一部であり、インタプリタと JVM クラスへのコンパイルの両方で利用できます (WASM バックエンドでは利用できません)。[Java 連携ガイド](../../guides/java-interop.md#errors-and-non-local-exits)を参照してください。

```lisp
(handler-case (java:static "java.lang.Integer" "parseInt" "x")
  (java:java-exception (e)
    (java:call (java:call (java:java-exception-cause e) "getClass") "getName")))
; => "java.lang.NumberFormatException"
```

`Integer.parseInt` は `NumberFormatException` を投げ、ハンドラはコンディションが保持する例外からそのクラスを読み取ります。

Java を呼び出す Clojure ファイルを読み込んだ場合、そのファイルが投げる例外も `java:java-exception` です。その cause は、例外のメッセージと cause から一度だけ作られる、例外のクラスのホスト例外です (`ex-info` の例外では `RuntimeException`)。
