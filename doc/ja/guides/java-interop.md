# Java 連携 (Java Interop)

`java` パッケージは、リフレクションを使って rontolisp から任意の Java API を操作できるようにします。オブジェクトの生成、インスタンスメソッドや静的メソッドの呼び出し、フィールドの読み取り、そして rontolisp のラムダを Java のインターフェース実装へ変換することができます。`examples/` の Swing デモ (`java-interop.lisp`、`swing.lisp`、`life-gui.lisp`) は、専用の Java グルーコードを一切書かずにこのパッケージだけでウィンドウを画面に表示しています。

> **JVM 専用 (インタプリタとコンパイル済み `.class`)。** 連携で得られる値はホストオブジェクトへの不透明な参照であるため、本物の JVM が必要です。動作するのは **JVM 上のインタプリタ** (`java -jar rontolisp.jar program.lisp`) と **JVM コンパイル済みプログラム** (`-o Prog.class` でコンパイルし `java Prog` で実行) です — コンパイラが解決した呼び出しは生成クラスの中の直接呼び出しになり、実行時解決に回る呼び出しのためにだけ、コンパイラは小さなリフレクションブリッジを生成クラスの隣 (`Prog$JavaBridge.class`、`-o prog.jar` ではその中のエントリー) に書き出します。そのときプログラムの実行にはそれがクラスパス上に必要です (必要な JRE は [Java リリースやクラスパスを指定したコンパイル](#compiling-against-a-java-release-or-a-class-path) を参照)。WASM バックエンドはホスト参照を表現できないため、`java:` を `.wasm` にコンパイルすると従来どおり `Cannot compile: java:...` エラーになります。GraalVM ネイティブバイナリ (`rontolisp program.lisp`) は `java:` プログラムを `.class` に**コンパイルする**ことはできますが、**インタプリタ実行**はできません。ネイティブイメージにはビルド時にリフレクション登録されたクラス・メンバーしか含まれず、rontolisp のビルドは連携用に何も登録していないため、`(java:static "java.lang.Math" "max" 3 7)` ですら `No such class` で失敗します。

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

## 実行前の呼び出し解決

インタプリタもコンパイル済みクラスも、すべての呼び出しを上の同じ規則で解決します。クラスがプログラムのテキストから分かる呼び出し (`java:new` や `java:static` が名指すクラス、`java:call` のレシーバの型) は、最初に実行される前に一度だけ、そのクラスのメソッドの中から解決されます。引数の種別も分かればただ 1 つのメソッドへ、分からなければ引数が選びうるオーバーロードの集合へ解決され、呼び出しは実行のたびに引数の種別でその中から選びます。手本は Clojure の型ヒント付き連携で、それをインタプリタにも適用しています。レシーバのクラスが分からない呼び出しは、実行時にレシーバのクラスと引数の種別から解決されます。選ばれるメソッドはどちらでも同じです。例外は後述の 1 つだけです。

プログラムのテキストが値について示すもの:

- リテラルの種別: `3`、`2.5`、`"x"`、`#\a`、`t`、`nil`、`lambda`
- `(java:new "C" ...)` はちょうど `C` である
- 解決済みの呼び出しの値は、そのメソッドが宣言する型を持つ。`StringBuilder` の `append` は `StringBuilder` を返すので、呼び出しの連鎖は 1 段ずつ解決される。`Object` を返すと宣言されたメソッドは何も示さない
- `(the (java:object "C") x)` と `(declare (type (java:object "C") v))` は、その値が `C` (または `nil`) であることを示す。`C` は `java:new` と同じくバイナリクラス名 (`java.util.Map$Entry`) で書く。`(java:object "C" :exact)` は、`java:new` の戻り値と同じく、値がちょうど `C` であり `nil` ではないことを示す
- `let` / `let*` の変数は初期化式の型を持つ。ただし special 変数である場合と、スコープ内のどこか (クロージャ内を含む) で `setq`、`setf`、`incf` などにより代入される場合を除く
- `(declaim (type (java:object "C") v))` は、それ以降のフォームで大域変数 `v` の型を示す。`defvar` の初期値は型を示さない。どのフォームもその変数に代入しうるため

宣言された型は信頼されます。`C` でない値は、レシーバでも引数でも、呼び出しに渡った時点でエラーになります。選ばれていないメソッドに合わせて変換されることはありません。

```lisp
(defun total-length (sb)
  (declare (type (java:object "java.lang.StringBuilder") sb))
  (java:call sb "length"))
(total-length (java:new "java.lang.StringBuilder" "abc"))   ; => 3
```

```console
(defun parse (s)
  (declare (type (java:object "java.lang.String") s))
  (java:static "java.lang.Integer" "parseInt" s))
(parse 42)   ; error: java:static: argument 1 is not a java.lang.String, got 42
```

次の 2 つの呼び出しはどちらも実行前に解決されます。`sb` はちょうど `StringBuilder` です。

```lisp
(let ((sb (java:new "java.lang.StringBuilder" "ab")))
  (java:call sb "reverse")
  (java:call sb "toString"))   ; => "ba"
```

コンパイル済みクラスは、1 つのメソッドへ解決された呼び出しをそのメソッドの直接呼び出しにし、オーバーロードの集合へ解決された呼び出しを、引数の種別の比較とそれが選ぶオーバーロードの直接呼び出しにします (どちらもリフレクションなし)。リフレクションブリッジは実行時解決に回る呼び出しのためにだけ書き出します。インタプリタも解決済みの呼び出しを同じく実行します。選ばれたメソッドを呼び、引数を検査して変換します。

```lisp
(defun bigger (a b) (java:static "java.lang.Math" "max" a b))
(list (bigger 3 7) (bigger 2.5 1) (bigger #\a 1))   ; => (7 2.5 97)
```

`a` と `b` は何でもありうるので、`bigger` の呼び出しはそのたびに `max(int,int)`、`max(long,long)`、`max(float,float)`、`max(double,double)` の中からコスト規則で選びます。

引数が実行前にメソッドを決めるのは、その引数が取りうるすべての種別が同じメソッドを選ぶときだけです。`String` の戻り値は `nil` でありえて、`nil` は `append(boolean)` を選ぶため、`(java:call sb "append" (java:call sb "toString"))` は実行時に `append(String)` と `append(boolean)` のどちらかを選びます。

### 宣言されたレシーバのクラスが候補を決める

宣言クラス `C` のレシーバに対する呼び出しは、Java と同じく `C` のメソッドの中から解決されます。初期化式の型が `C` である `let` 変数に対する呼び出しも同じです。実行時クラスだけが追加する同名の public オーバーロードは、引数の種別が実行前に分かるか実行時に分かるかによらず候補になりません。実行前の解決と実行時の解決で選択が異なるのはこの場合だけです。

```lisp
(defun remove-one (c)
  (declare (type (java:object "java.util.Collection") c))
  (java:call c "remove" 1))
(let ((a (java:new "java.util.ArrayList")) (b (java:new "java.util.ArrayList")))
  (dolist (x (list 10 20 1)) (java:call a "add" x) (java:call b "add" x))
  (remove-one a)             ; Collection.remove(Object): removes the element 1
  (java:call b "remove" 1)   ; ArrayList.remove(int): removes the element at index 1
  (list (java:call a "toString") (java:call b "toString")))
; => ("[10, 20]" "[10, 1]")
```

### パラメータタグ

メソッド名、および `java:new` のクラス名にはパラメータ型を付けられます。これはオーバーロードを直接指定します: `"max(long,long)"`、`"java.lang.StringBuilder(int)"`。`_` は任意の型に一致し、そのパラメータはコスト規則に任せます。パッケージのない型名は `java.lang` の型です。`T[]` または `T...` は配列です。

```lisp
(java:static "java.lang.String" "valueOf(int)" #\a)   ; => "97"
```

```lisp
(java:static "java.lang.Math" "max(long,_)" 3 7)   ; => 7
```

```lisp
(java:call (java:new "java.lang.StringBuilder(int)" 64) "capacity")   ; => 64
```

### リフレクション警告

`(setq java:*warn-on-reflection* t)` は、それ以降のフォームで実行時解決に回る呼び出しを理由とともに報告します。インタプリタはフォームを読み込むときに (フォームの行番号付きで)、コンパイラはコンパイル時に (呼び出しの位置付きで) 報告します。`--warn-java-reflection` は最初から有効にします。

```console
$ rontolisp --warn-java-reflection len.lisp -o Len.class
len.lisp:1:16: warning: java:call "length" is resolved by reflection at run time: the receiver's class is not known
```

### Java リリースやクラスパスを指定したコンパイル

インタプリタは実行中のクラスに対して解決します。JVM コンパイラは代わりにクラスファイルを読みます。JDK の `lib/ct.sym` (実行中の JDK のもの、なければ `JAVA_HOME` のもの、なければ `PATH` 上の `java` のもの) から、その JDK が持つ最新のリリース、または `--java-release N` のリリースを読み、続いて `--java-classpath` のディレクトリと jar を探します。コンパイル済みクラスはコンパイル時に選ばれたメソッドを呼ぶので、そのリリース向けに刻印されます (クラスバージョン 44 + N、最低でも Java 17 の 61)。そのリリースより古い JRE はクラスの読み込みを拒否します。コンパイル時に見えないクラスを名指す呼び出しは実行時に解決され、その呼び出しが使うリフレクションブリッジには、rontolisp をビルドした JRE と同等以上に新しい JRE が必要です。

```console
$ rontolisp app.lisp -o app.jar --java-release 21 --java-classpath lib/guava.jar
```

### リフレクションなしのコンパイル

`--java-static` は、リフレクションを必要とする呼び出しをすべてコンパイルエラーにします。実行時解決に回る呼び出し、`java:proxy`、そしてインターフェースが期待される箇所に渡した関数 (`java.lang.reflect.Proxy` になる) が該当します。種別が実行時にしか分からない引数は関数でありうるので、そうした呼び出しは、そこでインターフェースを期待するオーバーロード (`String.join(CharSequence, Iterable)` など) があればリフレクションを必要とします。コンパイルはそれらを一度にすべて列挙します。コンパイルが通ったものはリフレクションを含まないので、GraalVM の `native-image` はその jar をリーチャビリティメタデータなし (`reflect-config.json` もエージェント実行も不要) で実行ファイルにビルドできます。

```console
$ rontolisp app.lisp --java-static -o app.jar
$ native-image --no-fallback -jar app.jar -o app
$ ./app
```

```console
$ rontolisp len.lisp --java-static -o len.jar
error: --java-static: 1 java: call cannot be compiled without reflection:
  len.lisp:1:16: java:call "length": it is resolved by reflection at run time: the receiver's class is not known
```

こうした呼び出しを解決させるのは `(declare (type (java:object "C") v))` や `(the (java:object "C") x)` です。

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

`examples/jvm/java-interop.lisp` はこのパッケージだけで小さなウィンドウを構築します (ディスプレイのあるマシンで、インタプリタ実行するか `.class` にコンパイルして実行してください)。

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

`examples/jvm/swing.lisp` はこの 5 つの関数の上に再利用可能なグリッドウィンドウのヘルパーを構築しています。ヘルパーは独自の `swing` [パッケージ](../reference/packages.md)にまとめられており、`(require :swing "swing.lisp")` で取り込みます。`examples/jvm/life-gui.lisp` はこれを使って (`swing:grid-window`、`swing:paint`、...) ライフゲームをアニメーション表示します。

## ネイティブイメージ

コンパイル済みの `java:` プログラムは GraalVM ネイティブイメージにビルドできます。すべての呼び出しが実行前に解決されるプログラムには何も要りません。`--java-static` でコンパイルし ([リフレクションなしのコンパイル](#compiling-without-reflection))、その jar をそのままビルドしてください。実行時解決に回る呼び出しはリフレクションを使うので到達可能性メタデータが必要で、トレーシングエージェントが実行から記録します。

```bash
rontolisp prog.lisp -o prog.jar
java -agentlib:native-image-agent=config-output-dir=config -jar prog.jar
native-image -jar prog.jar -H:ConfigurationFileDirectories=config
```

メタデータがカバーするのはトレースした実行が行った呼び出しだけです。その実行が選ばなかったオーバーロードを選ぶ呼び出しは、イメージ内で `MissingReflectionRegistrationError` になります。たとえばクラスの分からない `sb` に対する `(java:call sb "append" x)` に、整数だけを渡した実行の後で浮動小数点数を渡した場合です。プログラムが使うすべての呼び出しの形を通る実行でトレースするか、型を宣言して呼び出しを解決させてください。

## 制限

- **JVM 専用**。インタプリタ (`java -jar rontolisp.jar`) と JVM コンパイル済みクラス (`java Prog`) で動作します。WASM バックエンドでは動作せず、連携クラスのリフレクションメタデータを持たない GraalVM ネイティブバイナリでのインタプリタ実行もできません (ネイティブバイナリで `java:` プログラムを `.class` に*コンパイルする*ことは可能です)。
- コンパイル済みクラスでは 5 つの関数は呼び出し位置でのみ使えます。第一級の関数値を持たないため、`#'java:call` や `(funcall 'java:new ...)` はコンパイルエラーになります (代わりに自前の `defun` でラップしてください)。埋め込み `eval` ランタイムもこれらを認識しません。また `java:` を使うコンパイル済みプログラムの実行には、呼び出しを解決したリリースの JRE が必要で、実行時解決に回る呼び出しを含むものには、rontolisp をビルドした JRE と同等以上に新しい JRE が必要です。
- シンボル、ハッシュテーブル、ドット対 (非真リスト)、多次元 (ランク 2 以上) の配列はマーシャリングされません。代わりに `java:new`/`java:call` で構築した Java コレクションとして渡してください。
- 返された `java.util.List` は (Java 配列と異なり) 不透明な `java` オブジェクトのままです。同一性と可変性が保たれるため、リスト関数ではなく `java:call` (`"get"`、`"size"` など) で読み取ってください。
- オーバーロード解決は引数コストによるもので、Java の完全な型推論規則ではありません。曖昧な呼び出しは曖昧性エラーを出さず、最小コスト (次に最小シグネチャ) の候補に解決されます。パラメータタグでオーバーロードを明示できます。
- これは完全なホストリフレクションブリッジであり任意の Java コードを実行できます。`java:` を使うプログラムは他の JVM プログラムと同じ信頼度で扱ってください。
