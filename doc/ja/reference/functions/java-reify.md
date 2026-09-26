# java:reify

`(java:reify "fully.qualified.Interface" "method" function ...)`

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

## 関数が返す値

引数と同じ規則で変換されます。整数は `int` に、リストは配列か `List` に、`nil` は `false` か `null` になる、という具合です。ただし関数は戻る方向ではプロキシにしません。インターフェースが期待される戻り値には `java:reify` か `java:proxy` のオブジェクトを返してください。変換できない値はエラーです。たとえば `java:reify: cannot return "x" as int from java.util.function.IntSupplier.getAsInt` です。

## コンパイル済みプログラムでの扱い

インターフェース名とメソッド名がリテラル文字列で、そのインターフェースがコンパイル時に見える `java:reify` は、コンパイル時に生成するクラス (プログラムの隣の `Prog$Reify0.class`) になります。リフレクションを使わないので、そのプログラムは `--java-static` でコンパイルでき、GraalVM ネイティブイメージにも設定なしでビルドできます。オブジェクトの表示は、コンパイル時は `#<java Prog$Reify0>`、インタプリタでは `java.lang.reflect.Proxy` のクラス名になります。名前を計算で与える `java:reify` は、実行時にリフレクションブリッジを通して実装されます。
