# java:new

`(java:new "fully.qualified.ClassName" args...)`

リフレクションでホスト (Java) オブジェクトを生成します。引数に最も適合するパラメータを持つコンストラクタを選び、`#<java <class-name>>` と表示される不透明な `java` オブジェクトを返します。JVM 専用の `java` 連携パッケージの一部であり、インタプリタと JVM クラスへのコンパイルの両方で利用できます (WASM バックエンドでは利用できません)。また実行時にクラスが存在しリフレクション可能である必要があります。[Java 連携ガイド](../../guides/java-interop.md)を参照してください。

```lisp
(java:call (java:new "java.lang.StringBuilder" "ab") "length")   ; => 2
```

文字列 `"ab"` から `java.lang.StringBuilder` を生成し、その `length` メソッドが `2` を返します。

クラス名にはコンストラクタのパラメータ型を付けて直接指定できます: `(java:new "java.lang.StringBuilder(int)" 64)`。呼び出しは実行前に一度だけ解決されます。引数の種別がテキストから分かればただ 1 つのコンストラクタへ、分からなければコンストラクタの集合へ解決され、実行時に引数の種別でその中から選びます (ガイドの[実行前の呼び出し解決](../../guides/java-interop.md#resolving-calls-before-they-run))。

引数の後ろには、関数の引数を Java がラムダを変換するのと同じ形で変換する `:functional` (ガイドの [java:proxy によるコールバック](../../guides/java-interop.md#callbacks-via-javaproxy)) 、その関数に Java の false を `|false|` として渡す `:java-false` (ガイドの [Java の false を受け取る](../../guides/java-interop.md#javas-false-back-java-false))、`byte[]` をそのオクテットを持つ `(unsigned-byte 8)` のベクタとして渡す `:octets` (ガイドの[バイト列を受け取る](../../guides/java-interop.md#octets-back-octets)) を置けます。
