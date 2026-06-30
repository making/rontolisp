# Java 連携 (Java Interop)

`java` パッケージは、リフレクションを使って rontolisp から任意の Java API を操作できるようにします。オブジェクトの生成、インスタンスメソッドや静的メソッドの呼び出し、フィールドの読み取り、そして rontolisp のラムダを Java のインターフェース実装へ変換することができます。`examples/` の Swing デモ (`java-interop.lisp`、`swing.lisp`、`life-gui.lisp`) は、専用の Java グルーコードを一切書かずにこのパッケージだけでウィンドウを画面に表示しています。

> **JVM インタプリタ専用。** 連携で得られる値はホストオブジェクトへの不透明な参照であり、これに対応するバイトコードや WASM の表現が存在しないため、JVM クラスバックエンドと WASM バックエンドはコンパイルできません (`java:` を使うフォームをコンパイルすると `Cannot compile: java:...` エラーになります)。さらにクラスをリフレクションで読み込んで呼び出すため、動作するのは **JVM 上のインタプリタ** (`java -jar rontolisp.jar program.lisp`) のみで、GraalVM ネイティブバイナリ (`rontolisp program.lisp`) では **動きません**。ネイティブイメージにはビルド時にリフレクション登録されたクラス・メンバーしか含まれず、rontolisp のビルドは連携用に何も登録していないため、`(java:static "java.lang.Math" "max" 3 7)` ですら `No such class` で失敗します。`java:` は JVM の jar 限定の機能だと考えてください。

## 関数

このパッケージは Common Lisp の一部ではないため、関数は `java:` 修飾子付きで参照します (または `(in-package java)` 後は修飾なし)。

| 関数 | 用途 |
|----------|---------|
| `java:new` | ホストオブジェクトの生成: `(java:new "fqcn" args...)` |
| `java:call` | インスタンスメソッドの呼び出し: `(java:call obj "method" args...)` |
| `java:static` | 静的メソッドの呼び出し: `(java:static "fqcn" "method" args...)` |
| `java:field` | 静的・インスタンスフィールドの読み取り: `(java:field class-or-obj "name")` |
| `java:proxy` | callable をインターフェースへ適合: `(java:proxy "iface" callable)` |

生成・返却されたオブジェクトは `#<java <class-name>>` という不透明な形で表示され、`java:call`/`java:field` に再び渡せます。

```lisp
(java:call (java:new "java.lang.StringBuilder" "ab") "length")   ; => 2
```

```lisp
(java:static "java.lang.Math" "max" 3 7)   ; => 7
```

```lisp
(java:field "java.lang.Integer" "MAX_VALUE")   ; => 2147483647
```

## 値のマーシャリング

引数と結果は rontolisp と Java の間で自動変換されます。

| rontolisp | Java (入力) | Java (出力) |
|-----------|-----------|------------|
| integer | `int`/`long`/`short`/`byte`/`float`/`double` (およびそのボックス型) | `int`/`long`/... → integer |
| float | `double`/`float` (およびボックス型) | `double`/`float` → float |
| string | `String`、長さ 1 なら `char` | `String` → string |
| character | `char`/`Character` | `Character` → character |
| `t` / `nil` | `boolean` (`nil` は任意の `null` 参照にもなる) | `boolean` → `t`/`nil` |
| `java` オブジェクト | ラップされたホストオブジェクト | その他のオブジェクト → `java` オブジェクト |
| 関数/ラムダ | 一致するインターフェースに対する `java:proxy` | — |

Java の `null` (および `void` メソッド) は `nil` として返ります。Lisp のリスト、シンボル、配列、ハッシュテーブルはマーシャリング **されません**。

## オーバーロード解決

クラスに同名・同アリティのコンストラクタやメソッドが複数ある場合、`java` は引数の変換 **総コストが最小** となるオーバーロードを選びます。完全一致は拡大変換より優先され、拡大変換はロッシー/ボックス化された変換より優先されます。同点は安定したシグネチャ順序で決まります。したがって整数引数は `long`/`double` より `int` パラメータを好み、リフレクションがメソッドを返す順序に結果が左右されることはありません。

```lisp
;; Math.max is overloaded for int/long/float/double; an integer picks int,
;; so the result is an integer, not a float.
(java:static "java.lang.Math" "max" 3 7)   ; => 7
```

整数のオーバーロードが存在しない場合、整数は利用可能な型へ変換されます。

```lisp
(java:static "java.lang.Math" "sqrt" 16)   ; => 4.0
```

## java:proxy によるコールバック

`java:proxy` は rontolisp の callable を背後に持つホストインターフェースのインスタンスを作ります。callable は各インターフェースメソッドに対して `(callable "method-name" arg...)` の形で適用されるため、1 つのラムダでインターフェース全体を実装し、メソッド名で振り分けることができます。戻り値はメソッドの戻り型へマーシャリングされます (`void` メソッドは無視します)。

```lisp
;; A java.util.function.Supplier whose get() returns a rontolisp value.
(java:call (java:proxy "java.util.function.Supplier" (lambda (method) 42)) "get")
; => 42
```

インターフェースが期待される箇所に callable を直接渡すと自動的に proxy でラップされます。これにより Swing の `ActionListener` を素のラムダで書けます。

```console
(java:call button "addActionListener"
  (lambda (method event) (handle-click)))
```

## Swing の例

`examples/java-interop.lisp` はこのパッケージだけで小さなウィンドウを構築します (ディスプレイのあるマシンでインタプリタ実行してください)。

```console
(defvar *frame* (java:new "javax.swing.JFrame" "java interop"))
(defvar *label* (java:new "javax.swing.JLabel" "click count: 0"))
(defvar *button* (java:new "javax.swing.JButton" "Increment"))
(defvar *count* 0)

(java:call *button* "addActionListener"
  (java:proxy "java.awt.event.ActionListener"
    (lambda (method event)
      (setq *count* (+ *count* 1))
      (java:call *label* "setText"
        (concatenate 'string "click count: " (princ-to-string *count*))))))

(java:call *frame* "setDefaultCloseOperation"
  (java:field "javax.swing.WindowConstants" "DISPOSE_ON_CLOSE"))
(java:call *frame* "setSize" 360 180)
(java:call *frame* "setVisible" t)
```

`examples/swing.lisp` はこの 5 つの関数の上に再利用可能なグリッドウィンドウのヘルパーを構築し、`examples/life-gui.lisp` はそれを使ってライフゲームをアニメーション表示します。

## 制限

- **JVM インタプリタ専用** (`java -jar rontolisp.jar`)。WASM/JVM クラスのコンパイラバックエンドでは動作せず、連携クラスのリフレクションメタデータを持たない GraalVM ネイティブバイナリでも動作しません。
- Lisp のリスト、シンボル、配列、ハッシュテーブルはマーシャリングされません。代わりに `java:new`/`java:call` で構築した Java コレクションとして渡してください。
- 可変長引数と配列パラメータはサポートされません。
- オーバーロード解決は引数コストによるもので、Java の完全な型推論規則ではありません。曖昧な呼び出しは曖昧性エラーを出さず、最小コスト (次に最小シグネチャ) の候補に解決されます。
- これは完全なホストリフレクションブリッジであり任意の Java コードを実行できます。`java:` を使うプログラムは他の JVM プログラムと同じ信頼度で扱ってください。
