# java:call

`(java:call object "methodName" args...)`

リフレクションで `java` オブジェクトのインスタンスメソッドを呼び出します。引数に最も適合するパラメータを持つオーバーロードを選び、マーシャリングされた結果を返します (`void` メソッドは `nil` を返します)。JVM インタプリタ専用の `java` 連携パッケージの一部であり、`java:` フォームのコンパイルはエラーになります。[Java 連携ガイド](../../guides/java-interop.md)を参照してください。

```lisp
(let ((lst (java:new "java.util.ArrayList")))
  (java:call lst "add" 7)
  (java:call lst "size"))
; => 1
```

`java.util.ArrayList` を生成し、要素を 1 つ追加してから `size` が要素数を返します。
