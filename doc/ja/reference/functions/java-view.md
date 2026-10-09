# java:view

`(java:view value items shape &optional printer order class)`

`value` を代理する読み取り専用の Java コレクションを作ります。要素は `items` を `Object` 引数と同じ規則で変換したもので、変換は作るときに一度だけ行います。`shape` はキーワードです。

| `shape` | Java のオブジェクト | `items` |
|---------|-------------|---------|
| `:list` | `java.util.List` | 真リストかベクタ |
| `:vector` | `RandomAccess` かつ `Comparable` でもある `java.util.List` | 真リストかベクタ |
| `:set` | `java.util.Set` (重複した要素は一つ) | 真リストかベクタ |
| `:map` | `java.util.Map` | ハッシュテーブルか plist |

Java はこれらのインターフェースを通して読みます。`equals` と `hashCode` はそれぞれの規約どおりで、書き込みはすべて `UnsupportedOperationException` を投げます。`toString` は `(printer value)` が返す文字列です (printer がなければ Java の綴り)。`:vector` のビューは `order` で順序付けられます。`order` は [`java:handle`](java-handle.md) の順序関数と同じく、値と比較相手のオブジェクトの Lisp の値で呼ばれます。`order` がなければ何とも順序付けられません。`class` は Java のメッセージがビューを呼ぶクラス名です。Java がビューを返すところではどこでも、`java:` は `value` を返します。JVM 専用の `java` 連携パッケージの一部であり、インタプリタと JVM クラスへのコンパイルの両方で利用できます (WASM バックエンドでは利用できません)。[Java 連携ガイド](../../guides/java-interop.md#views-javaview)を参照してください。

```lisp
(let* ((items (list 1 "two"))
       (v (java:view items items :list (lambda (x) (format nil "<~{~A~^ ~}>" x))))
       (l (java:new "java.util.ArrayList")))
  (java:call l "add" v)
  (list (java:call v "size") (java:call l "toString") (eq (java:call l "get" 0) items)))
; => (2 "[<1 two>]" T)
```

```lisp
(handler-case (java:static "java.util.Collections" "sort" (java:view 'v (list 3 1 2) :list))
  (error (e) (princ-to-string e)))
; => "error calling java.util.Collections.sort: java.lang.UnsupportedOperationException"
```

引数としてのビューは、そのクラスのホストオブジェクトです。クラスが合う引数 (`List`、`Collection`、`Iterable`、`Object` など) には、ビューそのものが渡ります。Java の配列が期待される箇所では、`:list` と `:vector` のビューは要素の配列に変換されます。ただしこの変換は、ビューをそのまま渡す方法がすべて合わないときに限られるため、可変長引数のメソッドはビューを一つの要素として受け取ります。`:set` と `:map` のビューは、クラスが合わない箇所には渡りません。

```lisp
(let ((v (java:view 'v (list 1 2) :list)))
  (list (java:static "java.util.Arrays" "toString" v)
        (java:call (java:static "java.util.Arrays" "asList" v) "size")))
; => ("[1, 2]" 1)
```

4 つのどれでもない `shape`、シーケンスでない `items` (`:map` ではハッシュテーブルでも偶数長の plist でもないもの)、関数でも nil でもない `printer`、関数でも nil でもない `order` (関数を渡せるのは `:vector` だけ)、文字列でも nil でもない `class` はエラーです: `java:view expects (java:view value items :list|:vector|:set|:map [printer [order ["class"]]]), got :TREE`。`Object` に変換できない要素は `java:view: no Java value for X` になります。オブジェクトのクラスは `am.ik.rontolisp.runtime.RontoJava*View` です。インタプリタも同じクラスを使い、コンパイル済みプログラムはこのクラスを隣に置いて、リフレクションなしでビューを作ります。
