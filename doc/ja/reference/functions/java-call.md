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

`object` には Lisp の文字列・数値・文字・`t` も渡せます。`Object` 引数に渡したときの Java オブジェクトとして呼ばれます（文字列は `String`、`42` は `Integer`）。

```lisp
(java:call "abc" "codePointAt" 0)
; => 97
```

メソッド名にはパラメータ型を付けられます (`"append(CharSequence)"`)。レシーバのクラスがテキストから分かる呼び出し (`(java:new ...)`、宣言された戻り型、`(the (java:object "C") x)`、`(declare (type (java:object "C") v))`) は、実行前に一度だけ、そのクラスのメソッドの中から解決されます。引数の種別も分かればただ 1 つのメソッドへ、分からなければオーバーロードの集合へ解決され、実行時に引数の種別でその中から選びます (ガイドの[実行前の呼び出し解決](../../guides/java-interop.md#resolving-calls-before-they-run))。

インターフェースが期待される位置に渡した関数はその `java:proxy` になり、メソッド名を先頭に呼ばれます。引数の後ろを `:functional` で終えると、各抽象メソッドをメソッドの引数だけで実装します。`java:new` と `java:static` も同じ終わり方で同じく変換します (ガイドの [java:proxy によるコールバック](../../guides/java-interop.md#callbacks-via-javaproxy))。

引数の後ろ (`:functional` との前後は問いません) を `:java-false` で終えると、Java の false を `nil` ではなく `|false|` として返します (ガイドの [Java の false を受け取る](../../guides/java-interop.md#javas-false-back-java-false))。

```lisp
(java:call (java:new "java.util.ArrayList" (list 1)) "isEmpty" :java-false)
; => |false|
```

ほかのマーカーと並べて `:octets` で終えると、`byte[]` (結果、または返した配列の要素) を、符号付きバイトのリストではなく、そのオクテットを持つ `(unsigned-byte 8)` のベクタとして返します。呼び出しが変換する関数にも同じ形で渡します。このベクタは Java の配列そのものなので、関数が格納した値は Java から読めます (ガイドの[バイト列を受け取る](../../guides/java-interop.md#octets-back-octets))。

```lisp
(let ((o (java:new "java.io.ByteArrayOutputStream")))
  (java:call o "write" 200)
  (list (java:call o "toByteArray") (java:call o "toByteArray" :octets)))
; => ((-56) #(200))
```
