# java:proxy

`(java:proxy "fully.qualified.Interface"... callable)`

rontolisp の callable を背後に持つ、指定インターフェース (1 つ以上) のホストインスタンスを生成します。各インターフェースメソッドは `(callable "method-name" arg...)` として callable に振り分けられます。つまり callable の**第 1 引数には呼び出されたメソッドの名前**(文字列)が渡され、残りの引数がそのメソッドの実引数になります。callable の戻り値はメソッドの戻り型へマーシャリングされます (`void` メソッドは無視します。返した関数はプロキシにしないので、インターフェースが期待される戻り値には `java:proxy` か [`java:reify`](java-reify.md) のオブジェクトを返してください)。これにより rontolisp のラムダが Java のリスナーやコンパレータになります。単一メソッド (SAM) インターフェースではメソッド名は常に同じなので、慣習的に無視します (下記の例の `method` 引数)。JVM 専用の `java` 連携パッケージの一部であり、インタプリタと JVM クラスへのコンパイルの両方で利用できます (WASM バックエンドでは利用できません)。[Java 連携ガイド](../../guides/java-interop.md)を参照してください。

```lisp
(java:call (java:proxy "java.util.function.Supplier" (lambda (method) 42)) "get")
; => 42
```

`java.util.function.Supplier` をラムダで実装し、その `get` メソッドを呼ぶとラムダが実行されて `42` を返します。

## 関数型インターフェース

`java.util.function` 系を含め、任意のインターフェースで動作します。ラムダの第 1 引数はメソッド名を受け取り、残りの引数がメソッド引数を受け取ります。インターフェースの単一抽象メソッド (SAM) のアリティに合わせてください。

| インターフェース | SAM | ラムダの形 |
|-----------|-----|--------------|
| `Supplier` | `get()` | `(lambda (method) ...)` |
| `Function` | `apply(x)` | `(lambda (method x) ...)` |
| `Consumer` | `accept(x)` | `(lambda (method x) ...)` |
| `Predicate` | `test(x)` | `(lambda (method x) ...)` |
| `BiFunction` | `apply(a, b)` | `(lambda (method a b) ...)` |
| `BinaryOperator` | `apply(a, b)` | `(lambda (method a b) ...)` |
| `Comparator` | `compare(a, b)` | `(lambda (method a b) ...)` |

```lisp
;; Function<Integer,Integer>: apply(x) -> x + 1
(java:call (java:proxy "java.util.function.Function" (lambda (method x) (+ x 1))) "apply" 41)
; => 42
```

```lisp
;; BiFunction<Integer,Integer,Integer>: apply(a, b) -> a * b
(java:call (java:proxy "java.util.function.BiFunction" (lambda (method a b) (* a b))) "apply" 6 7)
; => 42
```

```lisp
;; Predicate<Integer>: test(x) -> even?
(java:call (java:proxy "java.util.function.Predicate" (lambda (method x) (evenp x))) "test" 4)
; => T
```

JDK 側が SAM メソッドを呼び出す場合でもプロキシは動作します。たとえば `HashMap.merge` は、渡された `BiFunction` を呼び出して古い値と新しい値を合成します。

```lisp
(let ((m (java:new "java.util.HashMap"))
      (mul (java:proxy "java.util.function.BiFunction" (lambda (method a b) (* a b)))))
  (java:call m "put" "x" 10)
  (java:call m "merge" "x" 5 mul)
  (java:call m "get" "x"))
; => 50
```

## デフォルトメソッド

動的プロキシは `BiFunction.andThen` や `Predicate.and` といったデフォルトメソッドも含め、**すべて**のメソッド呼び出しを callable に転送します。これらを呼ぶと、インターフェース本来のデフォルト実装を実行する代わりに `(callable "andThen" ...)` としてラムダに振り分けられるため、`(f.andThen g)` のようなコンビネータは利用できません。代わりに単一抽象メソッド (`apply`/`test`/`accept`/`get`/`compare`) を呼んでください。

メソッドごとに別の関数で実装し、デフォルトメソッドの本体を保つには [`java:reify`](java-reify.md) を使ってください。

## Java の false

Java の false は `nil` として callable に渡ります。callable の後ろを `:java-false` で終えると、代わりに `|false|` を渡します (ガイドの [Java の false を受け取る](../../guides/java-interop.md#javas-false-back-java-false))。

```lisp
(let ((seen nil))
  (java:call (java:proxy "java.util.function.Consumer" (lambda (method x) (push x seen)) :java-false)
             "accept" '|false|)
  seen)
; => (|false|)
```

## 複数のインターフェース

callable より前の名前はすべて、1 つのオブジェクトが実装するインターフェースです。Java はそのオブジェクトをどのインターフェースとしても保持できます。2 つのインターフェースが宣言する同じメソッド名は、Java がどちらのインターフェース経由で呼んでも、その 1 つの名前で callable に届きます。

```lisp
(let* ((seen nil)
       (p (java:proxy "java.util.function.Consumer" "java.util.function.IntConsumer"
            (lambda (method x) (push x seen)))))
  (java:call (java:static "java.util.List" "of" "a") "forEach" p)                ; Consumer.accept
  (java:call (java:static "java.util.stream.IntStream" "range" 0 1) "forEach" p) ; IntConsumer.accept
  (reverse seen))
; => ("a" 0)
```

各名前はインターフェースで、1 回だけ指定します (`java:proxy names interface I twice`)。オブジェクトの `toString` は `#<java-proxy I J>` です。`(java:call p "accept" 1)` のようなオブジェクト自体への呼び出しは実行時にそのクラスで解決され、いずれかのインターフェースが期待される箇所に渡す呼び出しは実行前に解決されます。

## コンパイル済みプログラムでの扱い

インターフェース名がリテラル文字列で、そのインターフェースがコンパイル時に見える `java:proxy` は、コンパイル時に生成するクラス (プログラムの隣の `Prog$Proxy0.class`) になります。インターフェースが期待される箇所に渡した関数も同じです。`java.lang.reflect.Proxy` を使わないので、そのプログラムは `--java-static` でコンパイルでき、GraalVM ネイティブイメージにも設定なしでビルドできます。表示は `#<java Prog$Proxy0>` で、インタプリタでは `java.lang.reflect.Proxy` のクラス名になります。実行時にインターフェース名を与える `java:proxy` はリフレクションブリッジを通ります。
