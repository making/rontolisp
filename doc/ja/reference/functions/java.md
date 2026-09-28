# java パッケージの関数

`java` パッケージは任意の Java API を操作します。**JVM 専用**であり、インタプリタ (`java -jar rontolisp.jar`) と JVM コンパイル済みクラス (コンパイル時に解決した呼び出しは直接呼び出しに、`java:reify` と `java:proxy` はそのために生成したクラスになり、それ以外はコンパイラが生成 `.class` の隣に書き出すリフレクションブリッジを通ります) で動作します (WASM バックエンドでは動作せず、GraalVM ネイティブバイナリはリフレクションメタデータを持たないためインタプリタ実行もできません)。また **Common Lisp の一部ではありません**。関数は `java:` 修飾子付きで参照します。各名前は個別のページにリンクしています。マーシャリング、オーバーロード解決、制限については [Java 連携ガイド](../../guides/java-interop.md)を参照してください。

| 関数 | 例 | 結果 |
|----------|---------|--------|
| `java:new` | `(java:new "java.lang.StringBuilder" "ab")` | ホストオブジェクト (`#<java ...>`) |
| `java:call` | `(java:call obj "size")` | マーシャリングされたインスタンスメソッドの結果 |
| `java:static` | `(java:static "java.lang.Math" "max" 3 7)` | マーシャリングされた静的メソッドの結果 |
| `java:field` | `(java:field "java.lang.Integer" "MAX_VALUE")` | マーシャリングされたフィールド値 |
| `java:proxy` | `(java:proxy "java.lang.Runnable" (lambda (m) ...))` | callable を背後に持つインターフェースのインスタンス |
| `java:reify` | `(java:reify "java.lang.Runnable" "run" (lambda () ...))` | メソッドごとに関数を持つインターフェースのインスタンス |

呼び出しの実行前解決のために、さらに 2 つのシンボルがあります。`the` と `declare` で使う型指定子 `(java:object "fqcn")` と、実行時解決に回る呼び出しを報告する変数 `java:*warn-on-reflection*` です (ガイドの[実行前の呼び出し解決](../../guides/java-interop.md#resolving-calls-before-they-run))。

