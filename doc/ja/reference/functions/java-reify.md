# java:reify

`(java:reify "fully.qualified.Interface" "method" function ...)`

`(java:reify '("Interface" ...) [:value value] [:class "class"] "method" function ...)`

指定インターフェースのホストインスタンスを、メソッドごとに実装して生成します。各 `"method"` はインターフェースのメソッドを 1 つ指し、その後ろの `function` がそのメソッドの引数で呼ばれます。[`java:proxy`](java-proxy.md) と異なり、メソッド名は渡されません。関数の値はメソッドの戻り型へ変換されます (`void` メソッドは無視します)。JVM 専用の `java` 連携パッケージの一部であり、インタプリタと JVM コンパイル済みクラスで利用でき、WASM バックエンドでは利用できません。[Java 連携ガイド](../../guides/java-interop.md#implementing-interfaces-with-javareify)を参照してください。

```lisp
(java:call (java:reify "java.util.function.Function" "apply" (lambda (x) (* x 10))) "apply" 4)
; => 40
```

## メソッドの指定

インターフェースの複数のメソッドが共有する名前には、`java:call` のメソッド名と同じくパラメータ型のタグを付けます: `"append(char)"`、`"append(CharSequence,int,int)"`。複数のメソッドに一致する名前や、どのメソッドにも一致しない名前はエラーです。

```lisp
(let* ((seen nil)
       (out (java:reify "java.lang.Appendable"
              "append(char)" (lambda (c) (push c seen) nil)
              "append(CharSequence)" (lambda (s) (push s seen) nil)
              "append(CharSequence,int,int)" (lambda (s start end) (push (subseq s start end) seen) nil))))
  (java:call out "append" #\a)
  (java:call out "append" "bc")
  (reverse seen))
; => (#\a "bc")
```

`toString`、`equals`、`hashCode` も指定できます。指定しなければ `equals` と `hashCode` は同一性で比較し、`toString` は `#<java-reify fully.qualified.Interface>` を返します。

## 指定のないメソッド

抽象メソッドは実装されず、呼ぶと `UnsupportedOperationException` を投げます (`java:reify: no implementation of java.util.Iterator.next()`)。デフォルトメソッドはインターフェースの本体を保つので、コンビネータも使えます。

```lisp
(let ((times-ten (java:reify "java.util.function.Function" "apply" (lambda (x) (* x 10))))
      (plus-one (java:reify "java.util.function.Function" "apply" (lambda (x) (+ x 1)))))
  (java:call (java:call times-ten "andThen" plus-one) "apply" 4))
; => 41
```

## 複数のインターフェース

インターフェース名のリストを渡すと、そのすべてを実装するオブジェクトを 1 つ作ります。Java はこれをどのインターフェースとしても扱えます。名前はどのインターフェースのメソッドでも指せ、2 つのインターフェースが同じパラメータ並びで宣言するメソッドは 1 つのメソッドとして扱います。各名前はインターフェースでなければならず、同じ名前を 2 度並べるとエラーです (`java:reify names interface I twice`)。並べた別のインターフェースが継承するインターフェースは、並べても何も変えません。オブジェクトの `toString` は、指定しなければ `#<java-reify I J>` です。

```lisp
(let* ((ran nil)
       (o (java:reify '("java.lang.Runnable" "java.util.function.Supplier")
            "run" (lambda () (setq ran t))
            "get" (lambda () 42))))
  (java:call (java:new "java.lang.Thread" o) "run")
  (list ran (java:call o "get")))
; => (T 42)
```

## 値を表すオブジェクト

インターフェースの直後に `:value` を置くと、オブジェクトは [`java:handle`](java-handle.md) と同じく Lisp の値を表します。Java がこのオブジェクトを返す箇所 (メソッドの戻り値、配列の要素、コールバックの引数) では、`java:` はその値を返します。`:class` は Java のメッセージがこのオブジェクトを呼ぶクラス名で、指定しなければオブジェクト自身のクラス名です。

```lisp
(let* ((cell (list :cell 1))
       (l (java:new "java.util.ArrayList")))
  (java:call l "add" (java:reify "java.lang.Runnable" :value cell :class "my.Cell" "run" (lambda () nil)))
  (eq (java:call l "get" 0) cell))
; => T
```

`equals` と `hashCode` は、指定しなければハッシュが nil のハンドルと同じです。同じクラス名でまったく同じ値を表すオブジェクトとだけ等しく、ハッシュはその値の同一性ハッシュです。`toString` は、指定しなければ `Object` と同じ書き方でクラス名とそのハッシュを並べます (`my.Cell@1b6d3586` など)。`value()` か `className()` を宣言するインターフェースは、このオブジェクトが値とクラス名を返すメソッドと衝突するので、`:value` を付けて実装できません (`java:reify: :value conflicts with I.value()`)。

## 関数が返す値

引数と同じ規則で変換されます。整数は `int` に、リストは配列か `List` に、`nil` は `false` か `null` になる、という具合です。ただし関数は戻る方向ではプロキシにしません。インターフェースが期待される戻り値には `java:reify` か `java:proxy` のオブジェクトを返してください。変換できない値はエラーです。たとえば `java:reify: cannot return "x" as int from java.util.function.IntSupplier.getAsInt` です。

Java の false は `nil` として関数に渡ります。最後の関数の後ろを `:java-false` で終えると、代わりに `|false|` を渡します (ガイドの [Java の false を受け取る](../../guides/java-interop.md#javas-false-back-java-false))。

```lisp
(let ((seen nil))
  (java:call (java:reify "java.util.function.Consumer" "accept" (lambda (x) (push x seen)) :java-false)
             "accept" '|false|)
  seen)
; => (|false|)
```

## コンパイル済みプログラムでの扱い

インターフェース名とメソッド名がリテラル文字列 (複数ならクォートしたリスト、`:class` もリテラル) で、そのインターフェースがコンパイル時に見える `java:reify` は、コンパイル時に生成するクラス (プログラムの隣の `Prog$Reify0.class`) になります。リフレクションを使わないので、そのプログラムは `--java-static` でコンパイルでき、GraalVM ネイティブイメージにも設定なしでビルドできます。オブジェクトの表示は、コンパイル時は `#<java Prog$Reify0>`、インタプリタでは `java.lang.reflect.Proxy` のクラス名になります。名前を計算で与える `java:reify` は、実行時にリフレクションブリッジを通して実装されます。
