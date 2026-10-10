# java:field

`(java:field class-or-object "fieldName")`

フィールドを読み取ります。クラス名文字列を渡すと静的フィールド (定数など) を読み取り (そうして名指したインスタンスフィールドはエラーになります)、`java` オブジェクトを渡すとそのインスタンスのフィールドを読み取ります。マーシャリングされた値を返します。JVM 専用の `java` 連携パッケージの一部であり、インタプリタと JVM クラスへのコンパイルの両方で利用できます (WASM バックエンドでは利用できません)。[Java 連携ガイド](../../guides/java-interop.md)を参照してください。

```lisp
(java:field "java.lang.Integer" "MAX_VALUE")   ; => 2147483647
```

静的定数 `Integer.MAX_VALUE` を読み取り、rontolisp の整数へマーシャリングします。

フィールド名の後ろを `:java-false` で終えると、Java の false を `nil` ではなく `|false|` として返します (ガイドの [Java の false を受け取る](../../guides/java-interop.md#javas-false-back-java-false))。

```lisp
(java:field "java.lang.Boolean" "FALSE" :java-false)   ; => |false|
```

`:octets` で終えると、`byte[]` のフィールドをそのオクテットを持つ `(unsigned-byte 8)` のベクタとして返します (ガイドの[バイト列を受け取る](../../guides/java-interop.md#octets-back-octets))。
