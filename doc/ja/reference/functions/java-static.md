# java:static

`(java:static "fully.qualified.ClassName" "methodName" args...)`

リフレクションで静的メソッドを呼び出します。引数に最も適合するパラメータを持つオーバーロードを選び、マーシャリングされた結果を返します。JVM 専用の `java` 連携パッケージの一部であり、インタプリタと JVM クラスへのコンパイルの両方で利用できます (WASM バックエンドでは利用できません)。[Java 連携ガイド](../../guides/java-interop.md)を参照してください。

```lisp
(java:static "java.lang.Math" "max" 3 7)   ; => 7
```

`Math.max` は `int`/`long`/`float`/`double` にオーバーロードされていますが、整数引数が `int` オーバーロードを選ぶため、結果は整数 `7` になります。

メソッド名にはパラメータ型を付けてオーバーロードを直接指定できます: `"max(long,long)"`、または `_` をコスト規則に任せる `"max(long,_)"`。引数の種別がテキストから分かる呼び出しは、実行前に一度だけ解決されます (ガイドの[実行前の呼び出し解決](../../guides/java-interop.md#resolving-calls-before-they-run))。
