# Java 連携 (Java Interop)

`java` パッケージは、リフレクションを使って rontolisp から任意の Java API を操作できるようにします。オブジェクトの生成、インスタンスメソッドや静的メソッドの呼び出し、フィールドの読み取り、そして rontolisp のラムダを Java のインターフェース実装へ変換することができます。`examples/` の Swing デモ (`java-interop.lisp`、`swing.lisp`、`life-gui.lisp`) は、専用の Java グルーコードを一切書かずにこのパッケージだけでウィンドウを画面に表示しています。

> **JVM 専用 (インタプリタとコンパイル済み `.class`)。** 連携で得られる値はホストオブジェクトへの不透明な参照であり、呼び出しはリフレクションで解決されるため、本物の JVM が必要です。動作するのは **JVM 上のインタプリタ** (`java -jar rontolisp.jar program.lisp`) と **JVM コンパイル済みプログラム** (`-o Prog.class` でコンパイルし `java Prog` で実行) です — コンパイラは小さなリフレクションブリッジを生成クラスに埋め込むため、出力は従来どおり単一の自己完結した `.class` ファイルのままです (`java:` を使うプログラムの実行には、rontolisp をビルドした JRE と同等以上に新しい JRE が必要です)。WASM バックエンドはホスト参照を表現できないため、`java:` を `.wasm` にコンパイルすると従来どおり `Cannot compile: java:...` エラーになります。GraalVM ネイティブバイナリ (`rontolisp program.lisp`) は `java:` プログラムを `.class` に**コンパイルする**ことはできますが、**インタプリタ実行**はできません。ネイティブイメージにはビルド時にリフレクション登録されたクラス・メンバーしか含まれず、rontolisp のビルドは連携用に何も登録していないため、`(java:static "java.lang.Math" "max" 3 7)` ですら `No such class` で失敗します。

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
| 真リスト / ベクタ | `T[]` (要素ごとに変換、プリミティブ配列も可)、または `List`/`Collection`/`Iterable` | 任意の Java 配列 → リスト |

Java の `null` (および `void` メソッド) は `nil` として返ります。Java の配列が期待される箇所に真リスト (または `make-array` で作ったランク 1 の配列) を渡すと、要素ごとに要素型へ変換されます (`int[]` などのプリミティブ配列も含む)。`List`/`Collection`/`Iterable` が期待される箇所では `java.util.List` になり、ネストしたリストは再帰的に変換されます。逆方向では、Java の **配列** の結果は Lisp のリストになりますが、返された `java.util.List` は不透明な `java` オブジェクトのままで、そのメソッドを呼び出して操作します。

```lisp
;; in: the list becomes a Collection
(java:static "java.util.Collections" "max" (list 3 9 4))   ; => 9
```

```lisp
;; in: (1 2 3) -> int[]; out: the int[] result -> a list
(java:static "java.util.Arrays" "copyOf" (list 1 2 3) 2)   ; => (1 2)
```

シンボル、ハッシュテーブル、ドット対 (非真リスト)、多次元 (ランク 2 以上) の配列はマーシャリング **されません**。

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

## 可変長引数 (varargs)

可変長引数メソッド (例: `String.format(String, Object...)`) には任意個の末尾引数を渡せます。末尾引数は自動的に varargs 配列へパックされます。固定アリティのオーバーロードが両方に一致する場合はそちらが優先され、varargs 位置に渡したリスト/ベクタは配列そのものとしても扱えます。

```lisp
;; 1 and "x" are packed into the Object... array
(java:static "java.lang.String" "format" "%s-%s" 1 "x")   ; => "1-x"
```

```lisp
;; the list is the CharSequence[] varargs array itself
(java:static "java.lang.String" "join" "-" (list "a" "b" "c"))   ; => "a-b-c"
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

`examples/java-interop.lisp` はこのパッケージだけで小さなウィンドウを構築します (ディスプレイのあるマシンで、インタプリタ実行するか `.class` にコンパイルして実行してください)。

```console
(defvar *frame* (java:new "javax.swing.JFrame" "java interop"))
(defvar *label* (java:new "javax.swing.JLabel" "click count: 0"))
(defvar *button* (java:new "javax.swing.JButton" "Increment"))
(defvar *panel* (java:new "javax.swing.JPanel" (java:new "java.awt.BorderLayout" 12 12)))
(defvar *count* 0)

(java:call *button* "addActionListener"
  (java:proxy "java.awt.event.ActionListener"
    (lambda (method event)
      (setq *count* (+ *count* 1))
      (java:call *label* "setText"
        (concatenate 'string "click count: " (princ-to-string *count*))))))

(java:call *panel* "add" *label* (java:field "java.awt.BorderLayout" "CENTER"))
(java:call *panel* "add" *button* (java:field "java.awt.BorderLayout" "SOUTH"))

(java:call *frame* "setContentPane" *panel*)
(java:call *frame* "setDefaultCloseOperation"
  (java:field "javax.swing.WindowConstants" "DISPOSE_ON_CLOSE"))
(java:call *frame* "setSize" 360 180)
(java:call *frame* "setVisible" t)
```

`examples/swing.lisp` はこの 5 つの関数の上に再利用可能なグリッドウィンドウのヘルパーを構築しています。ヘルパーは独自の `swing` [パッケージ](../reference/packages.md)にまとめられており、`(require :swing "swing.lisp")` で取り込みます。`examples/life-gui.lisp` はこれを使って (`swing:grid-window`、`swing:paint`、...) ライフゲームをアニメーション表示します。

## 制限

- **JVM 専用**。インタプリタ (`java -jar rontolisp.jar`) と JVM コンパイル済みクラス (`java Prog`) で動作します。WASM バックエンドでは動作せず、連携クラスのリフレクションメタデータを持たない GraalVM ネイティブバイナリでのインタプリタ実行もできません (ネイティブバイナリで `java:` プログラムを `.class` に*コンパイルする*ことは可能です)。
- コンパイル済みクラスでは 5 つの関数は呼び出し位置でのみ使えます。第一級の関数値を持たないため、`#'java:call` や `(funcall 'java:new ...)` はコンパイルエラーになります (代わりに自前の `defun` でラップしてください)。埋め込み `eval` ランタイムもこれらを認識しません。また `java:` を使うコンパイル済みプログラムの実行には、rontolisp をビルドした JRE と同等以上に新しい JRE が必要です。
- シンボル、ハッシュテーブル、ドット対 (非真リスト)、多次元 (ランク 2 以上) の配列はマーシャリングされません。代わりに `java:new`/`java:call` で構築した Java コレクションとして渡してください。
- 返された `java.util.List` は (Java 配列と異なり) 不透明な `java` オブジェクトのままです。同一性と可変性が保たれるため、リスト関数ではなく `java:call` (`"get"`、`"size"` など) で読み取ってください。
- オーバーロード解決は引数コストによるもので、Java の完全な型推論規則ではありません。曖昧な呼び出しは曖昧性エラーを出さず、最小コスト (次に最小シグネチャ) の候補に解決されます。
- これは完全なホストリフレクションブリッジであり任意の Java コードを実行できます。`java:` を使うプログラムは他の JVM プログラムと同じ信頼度で扱ってください。
