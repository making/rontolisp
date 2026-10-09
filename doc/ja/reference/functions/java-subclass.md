# java:subclass

`(java:subclass "fully.qualified.Superclass" '("iface"...) '("method"...) constructor-args... callable)`

スーパークラス（と追加のインターフェース）を継承するホストインスタンスを、rontolisp の callable を背後に持つ形で作ります。`java:proxy` はクラスを継承できません（`java.lang.reflect.Proxy` はインターフェースだけを実装します）。そのためクラスの proxy は生成したサブクラスであり、インタープリターでは実行時に定義し、コンパイル済みプログラムではコンパイル時に生成します。

名前を挙げた各メソッドは callable に `(callable this "method-name" arg...)` の形で振り分けられます。つまり callable の**第 1 引数はプロキシオブジェクト自身**（`this`）、第 2 引数は呼ばれたメソッドの名前（文字列）、残りはメソッドの引数です。戻り値はメソッドの戻り型へマーシャリングされます（`void` メソッドは無視し、返した関数はプロキシにしません。インターフェースが期待される戻り値には `java:reify` か `java:proxy` のオブジェクトを返します）。Clojure のクラスに対する `proxy` はこの形で書かれており、`proxy-super` は生成した `super$` アクセサーを通常のメソッドとして呼ぶことでスーパークラスの実装に届きます。

```lisp
(let ((f (java:subclass "java.io.File" '() '("lastModified") "recent"
            (lambda (this method &rest args) 42))))
  (java:call f "lastModified"))
; => 42
```

コンストラクタ引数は、共有のオーバーロード規則でスーパークラスのコンストラクタを選びます（`public` と `protected` のコンストラクタを含みます）。名前を挙げたメソッドは本体を実行します（`toString`/`equals`/`hashCode` を含みます。`Object` の 3 つを保つ `java:proxy` とは異なります）。名前を挙げなかったメソッドは、クラスの系譜に実装があれば継承し、実装がなければ呼ばれたときにメソッド名とともに `UnsupportedOperationException` を送出します。

```lisp
(let ((f (java:subclass "java.io.File" '() '("toString") "x"
            (lambda (this method &rest args) "over!"))))
  (list (java:call f "toString") (java:call f "getName") (java:call f "super$toString$0")))
; => ("over!" "x" "x")
```

callable の後ろを `:functional` で終えると、インターフェースが期待されるコンストラクタ引数の関数は、`java:proxy` としてではなくメソッドの引数だけで呼ばれる実装になります（`java:new` の `:functional` と同じです）。`:java-false` で終えると、callable に Java の false を `nil` ではなく `|false|` として渡します (ガイドの [Java の false を受け取る](../../guides/java-interop.md#javas-false-back-java-false))。

## コンパイル済みプログラムでは

スーパークラス・インターフェース・メソッド名がコンパイル時に見えるリテラル文字列である `java:subclass` は、コンパイル時に生成するクラスになります（プログラムの隣の `Prog$Subclass0.class`）。リフレクションを使わないため、構築は `--java-static` でコンパイルできます（オブジェクト自身に対する呼び出しは実行時にそのクラスで解決されます）。実行時まで残る `java:subclass`（実行時に計算する名前や、コンパイル時に見えないクラス）は名前を上げて拒否されます（ブリッジはクラスを生成しません）。インタープリターは実行時に解決します。詳しくは [Java 連携ガイド](../../guides/java-interop.md)を参照してください。
