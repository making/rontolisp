# java:field

`(java:field class-or-object "fieldName")`

リフレクションでフィールドを読み取ります。クラス名文字列を渡すと静的フィールド (定数など) を、`java` オブジェクトを渡すとそのインスタンスのフィールドを読み取ります。マーシャリングされた値を返します。JVM 専用の `java` 連携パッケージの一部であり、インタプリタと JVM クラスへのコンパイルの両方で利用できます (WASM バックエンドでは利用できません)。[Java 連携ガイド](../../guides/java-interop.md)を参照してください。

```lisp
(java:field "java.lang.Integer" "MAX_VALUE")   ; => 2147483647
```

静的定数 `Integer.MAX_VALUE` を読み取り、rontolisp の整数へマーシャリングします。
