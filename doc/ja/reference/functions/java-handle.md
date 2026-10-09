# java:handle

`(java:handle value "text" &optional hash "order")`

Java に値のない Lisp の値 (シンボル、リストなど) である `value` を代理する Java オブジェクトを作ります。Java からは `text` がそのオブジェクトの `toString` に見え、同じテキストのハンドル同士は `equals` で等しくなります。オブジェクトの `hashCode` は `hash` の下位 32 ビット (指定しなければテキストの `hashCode`) で、ハンドルは `order` (指定しなければテキスト) で順序付けられます。そのためハンドルは `java.util.HashMap` のキーになり、`java.util.TreeSet` の中で整列します。Java がハンドルを返すところ (メソッドの結果、配列の要素、コールバックの引数) ではどこでも、`java:` は `value` を返します。JVM 専用の `java` 連携パッケージの一部であり、インタプリタと JVM クラスへのコンパイルの両方で利用できます (WASM バックエンドでは利用できません)。[Java 連携ガイド](../../guides/java-interop.md#handles-javahandle)を参照してください。

```lisp
(let ((l (java:new "java.util.ArrayList")))
  (java:call l "add" (java:handle '(1 2) "pair"))
  (list (java:call l "toString") (java:call l "get" 0)))
; => ("[pair]" (1 2))
```

```lisp
(let ((s (java:new "java.util.TreeSet")))
  (java:call s "add" (java:handle 'a "a" 0 "2"))
  (java:call s "add" (java:handle 'b "b" 0 "1"))
  (java:call s "toString"))
; => "[b, a]"
```

文字列でない `text` や `order`、整数でない `hash` はエラーです: `java:handle expects (java:handle value "text" [hash ["order"]]), got 2`。コンパイル済みプログラムでは、ハンドルはプログラムの隣に生成したクラス (`Prog$Handle.class`) のオブジェクトになり、リフレクションを使いません。
