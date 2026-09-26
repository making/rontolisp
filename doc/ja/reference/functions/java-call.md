# java:call

`(java:call object "methodName" args...)`

リフレクションで `java` オブジェクトのインスタンスメソッドを呼び出します。引数に最も適合するパラメータを持つオーバーロードを選び、マーシャリングされた結果を返します (`void` メソッドは `nil` を返します)。JVM 専用の `java` 連携パッケージの一部であり、インタプリタと JVM クラスへのコンパイルの両方で利用できます (WASM バックエンドでは利用できません)。[Java 連携ガイド](../../guides/java-interop.md)を参照してください。

```lisp
(let ((lst (java:new "java.util.ArrayList")))
  (java:call lst "add" 7)
  (java:call lst "size"))
; => 1
```

`java.util.ArrayList` を生成し、要素を 1 つ追加してから `size` が要素数を返します。

メソッド名にはパラメータ型を付けられます (`"append(CharSequence)"`)。レシーバのクラスと引数の種別がテキストから分かる呼び出し (`(java:new ...)`、宣言された戻り型、`(the (java:object "C") x)`、`(declare (type (java:object "C") v))`) は、実行前に一度だけ、そのクラスのメソッドの中から解決されます (ガイドの[実行前の呼び出し解決](../../guides/java-interop.md#resolving-calls-before-they-run))。
