# java:new

`(java:new "fully.qualified.ClassName" args...)`

リフレクションでホスト (Java) オブジェクトを生成します。引数に最も適合するパラメータを持つコンストラクタを選び、`#<java <class-name>>` と表示される不透明な `java` オブジェクトを返します。JVM 専用の `java` 連携パッケージの一部であり、インタプリタと JVM クラスへのコンパイルの両方で利用できます (WASM バックエンドでは利用できません)。また実行時にクラスが存在しリフレクション可能である必要があります。[Java 連携ガイド](../../guides/java-interop.md)を参照してください。

```lisp
(java:call (java:new "java.lang.StringBuilder" "ab") "length")   ; => 2
```

文字列 `"ab"` から `java.lang.StringBuilder` を生成し、その `length` メソッドが `2` を返します。

クラス名にはコンストラクタのパラメータ型を付けて直接指定できます: `(java:new "java.lang.StringBuilder(int)" 64)`。引数の種別がテキストから分かる呼び出しは、実行前に一度だけ解決されます (ガイドの[実行前の呼び出し解決](../../guides/java-interop.md#resolving-calls-before-they-run))。
