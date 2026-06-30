# java:static

`(java:static "fully.qualified.ClassName" "methodName" args...)`

リフレクションで静的メソッドを呼び出します。引数に最も適合するパラメータを持つオーバーロードを選び、マーシャリングされた結果を返します。JVM インタプリタ専用の `java` 連携パッケージの一部であり、`java:` フォームのコンパイルはエラーになります。[Java 連携ガイド](../../guides/java-interop.md)を参照してください。

```lisp
(java:static "java.lang.Math" "max" 3 7)   ; => 7
```

`Math.max` は `int`/`long`/`float`/`double` にオーバーロードされていますが、整数引数が `int` オーバーロードを選ぶため、結果は整数 `7` になります。
