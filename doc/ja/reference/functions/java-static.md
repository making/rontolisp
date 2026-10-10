# java:static

`(java:static "fully.qualified.ClassName" "methodName" args...)`

静的メソッドを呼び出します。引数に最も適合するパラメータを持つオーバーロードを選び、マーシャリングされた結果を返します。候補はそのクラスの静的メソッドだけです。`(java:static "java.lang.String" "length")` はメソッドを見つけません。JVM 専用の `java` 連携パッケージの一部であり、インタプリタと JVM クラスへのコンパイルの両方で利用できます (WASM バックエンドでは利用できません)。[Java 連携ガイド](../../guides/java-interop.md)を参照してください。

```lisp
(java:static "java.lang.Math" "max" 3 7)   ; => 7
```

`Math.max` は `int`/`long`/`float`/`double` にオーバーロードされていますが、整数引数が `int` オーバーロードを選ぶため、結果は整数 `7` になります。

メソッド名にはパラメータ型を付けてオーバーロードを直接指定できます: `"max(long,long)"`、または `_` をコスト規則に任せる `"max(long,_)"`。呼び出しは実行前に一度だけ解決されます。引数の種別がテキストから分かればただ 1 つのメソッドへ、分からなければオーバーロードの集合へ解決され、実行時に引数の種別でその中から選びます (ガイドの[実行前の呼び出し解決](../../guides/java-interop.md#resolving-calls-before-they-run))。

引数の後ろ (`:functional` との前後は問いません) を `:java-false` で終えると、Java の false を `nil` ではなく `|false|` として返します (ガイドの [Java の false を受け取る](../../guides/java-interop.md#javas-false-back-java-false))。

```lisp
(java:static "java.lang.Boolean" "parseBoolean" "no" :java-false)   ; => |false|
```

`:octets` で終えると、`byte[]` をそのオクテットを持つ `(unsigned-byte 8)` のベクタとして返し、呼び出しが変換する関数にも同じ形で渡します (ガイドの[バイト列を受け取る](../../guides/java-interop.md#octets-back-octets))。Java へ `byte[]` を渡すには [`:bytes` のビュー](java-view.md)を使います。

```lisp
(java:static "java.util.Arrays" "copyOf"
             (java:view 'v (make-array 2 :element-type '(unsigned-byte 8) :initial-element 255) :bytes)
             3 :octets)
; => #(255 255 0)
```
