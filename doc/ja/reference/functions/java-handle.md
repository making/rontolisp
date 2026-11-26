# java:handle

`(java:handle value text &optional hash order class)`<br>
`(java:handle function :functional :java-false [:octets] [:through f])`

Java に値のない Lisp の値 (シンボル、リストなど) である `value` を代理する Java オブジェクトを作ります。Java からは `text` がそのオブジェクトの `toString` に見えます。`class` とテキストが同じハンドル同士は `equals` で等しくなります。オブジェクトの `hashCode` は `hash` の下位 32 ビット (指定しなければテキストの `hashCode`) で、ハンドルは同じクラスのハンドルを `order` (指定しなければテキスト) で順序付けます。そのためハンドルは `java.util.HashMap` のキーになり、`java.util.TreeSet` の中で整列します。Java がハンドルを返すところ (メソッドの結果、配列の要素、コールバックの引数) ではどこでも、`java:` は `value` を返します。JVM 専用の `java` 連携パッケージの一部であり、インタプリタと JVM クラスへのコンパイルの両方で利用できます (WASM バックエンドでは利用できません)。[Java 連携ガイド](../../guides/java-interop.md#handles-javahandle)を参照してください。

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

- `hash` に nil を渡すと、ハンドルは同じ `class` でまったく同じ `value` (`eq`) を表すオブジェクト (同様のハンドル、`:value` を付けた `java:reify`、関数そのものであるオブジェクト ([java:call](java-call.md))) とだけ等しくなり、`hashCode` はその値の同一性ハッシュになります。このとき `text` も nil にでき、`toString` は `Object` と同じくクラス名と 16 進のハッシュで綴られます (`my.Cell@1b6d3586`)。
- `order` には関数も渡せます。`compareTo` はその関数を値と比較相手のオブジェクトの Lisp の値で呼び、返された実数の符号を答えます。`order` が nil のハンドルは何とも順序付けられません。比較相手を順序付けられないとき、また別の `class` のハンドルと比べたときは、キャストが投げるのと同じ `ClassCastException` (`class S cannot be cast to class K`) を投げます。
- `class` は Java のメッセージがハンドルを呼ぶクラス名で、等価と順序を分けます。クラスの異なるハンドル同士は `equals` で等しくならず、比較もできません。
- 実数のハンドルは、その数の `java.lang.Number` です。`doubleValue` (分数は `DECIMAL64` で割った商)、`longValue`、`intValue` を答えます。

```lisp
(let ((s (java:new "java.util.TreeSet"))
      (by-value (lambda (r other) (if (realp other) (signum (- r other)) nil))))
  (dolist (r (list 1/2 1/3 3/4))
    (java:call s "add" (java:handle r (princ-to-string r) 0 by-value "my.Ratio")))
  (list (java:call s "toString") (java:call s "first")
        (java:call (java:handle 1/3 "1/3" 0 by-value "my.Ratio") "doubleValue")))
; => ("[1/3, 1/2, 3/4]" 1/3 0.3333333333333333)
```

関数と、`text` の位置に呼び出しのマーカー (`:functional` と `:java-false`、加えて `:octets` と `:through f` を任意の順で) を渡すと、ハンドルはそのマーカーで終わる呼び出しでの関数そのものであるオブジェクト ([java:call](java-call.md)) になります。関数の引数で `Runnable`・`Callable`・`Comparator` を実装し、`clojure.lang.AFunction` を名乗り、同じクラスでその関数を表すどのオブジェクトとも等しくなります。Java はこれをその呼び出しのオブジェクトと同じく呼びます。メンバーを選んだ呼び出しはないので、`f` には `resolved` として nil を渡します。

```lisp
(let* ((f (lambda () 42))
       (h (java:handle f :functional :java-false))
       (l (java:new "java.util.ArrayList")))
  (java:call l "add" h)
  (list (java:call h "call") (eq f (java:call l "get" 0))))
; => (42 T)
```

文字列でない `text` (hash があるときの nil を含む)、整数でも nil でもない `hash`、文字列・関数・nil のいずれでもない `order`、文字列でも nil でもない `class` はエラーです: `java:handle expects (java:handle value text [hash [order ["class"]]]), got 2`。マーカーの前の値が関数でないときもエラーです: `java:handle expects (java:handle function :functional :java-false [:octets] [:through f]), got 5`。オブジェクトのクラスは `am.ik.rontolisp.runtime.RontoJavaHandle` (数なら `RontoJavaNumberHandle`) です。インタプリタも同じクラスを使い、コンパイル済みプログラムはこのクラスを隣に置いて、リフレクションなしでハンドルを作ります。関数そのものであるオブジェクトは、コンパイル済みプログラムでは生成されたクラスのオブジェクトで、インタプリタと、`:through` の関数がリテラルの `#'name` でないときは `java.lang.reflect.Proxy` です。[java:view](java-view.md) も参照してください。
