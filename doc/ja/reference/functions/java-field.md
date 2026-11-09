# java:field

`(java:field class-or-object "fieldName" [value])`

フィールドを読み取ります。クラス名文字列を渡すと静的フィールド (定数など) を読み取り (そうして名指したインスタンスフィールドはエラーになります)、`java` オブジェクトを渡すとそのインスタンスのフィールドを読み取ります。マーシャリングされた値を返します。名前の後ろに値を渡すと、読み取りの代わりにフィールドへ書き込み、その値を返します。数値はプリミティブ数値型のフィールドへ縮小変換されます (`int` フィールドへの `2.9` は `2`)。フィールドの型が受け取れない値、final フィールド、クラスにないフィールドはエラーです。書き込みは常に実行時に解決されるため、`--java-static` では拒否されます。JVM 専用の `java` 連携パッケージの一部であり、インタプリタと JVM クラスへのコンパイルの両方で利用できます (WASM バックエンドでは利用できません)。[Java 連携ガイド](../../guides/java-interop.md)を参照してください。

```lisp
(java:field "java.lang.Integer" "MAX_VALUE")   ; => 2147483647
```

静的定数 `Integer.MAX_VALUE` を読み取り、rontolisp の整数へマーシャリングします。

```lisp
(let ((p (java:new "java.awt.Point" 1 2)))
  (list (java:field p "x" 5) (java:field p "x")))   ; => (5 5)
```

フィールド名の後ろを `:java-false` で終えると、Java の false を `nil` ではなく `|false|` として返します (ガイドの [Java の false を受け取る](../../guides/java-interop.md#javas-false-back-java-false))。

```lisp
(java:field "java.lang.Boolean" "FALSE" :java-false)   ; => |false|
```

`:octets` で終えると、`byte[]` のフィールドをそのオクテットを持つ `(unsigned-byte 8)` のベクタとして返します (ガイドの[バイト列を受け取る](../../guides/java-interop.md#octets-back-octets))。
