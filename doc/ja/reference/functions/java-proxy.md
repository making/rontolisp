# java:proxy

`(java:proxy "fully.qualified.Interface" callable)`

rontolisp の callable を背後に持つ、指定インターフェースのホストインスタンスを生成します。各インターフェースメソッドは `(callable "method-name" arg...)` として callable に振り分けられます。つまり callable の**第 1 引数には呼び出されたメソッドの名前**(文字列)が渡され、残りの引数がそのメソッドの実引数になります。callable の戻り値はメソッドの戻り型へマーシャリングされます (`void` メソッドは無視します)。これにより rontolisp のラムダが Java のリスナーやコンパレータになります。単一メソッド (SAM) インターフェースではメソッド名は常に同じなので、慣習的に無視します (下記の例の `method` 引数)。JVM インタプリタ専用の `java` 連携パッケージの一部であり、`java:` フォームのコンパイルはエラーになります。[Java 連携ガイド](../../guides/java-interop.md)を参照してください。

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
; => t
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
