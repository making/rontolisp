# Java 連携 (Java Interop)

`java` パッケージは、リフレクションを使って rontolisp から任意の Java API を操作できるようにします。オブジェクトの生成、インスタンスメソッドや静的メソッドの呼び出し、フィールドの読み取り、そして rontolisp のラムダを Java のインターフェース実装へ変換することができます。`examples/` の Swing デモ (`java-interop.lisp`、`swing.lisp`、`life-gui.lisp`) は、専用の Java グルーコードを一切書かずにこのパッケージだけでウィンドウを画面に表示しています。

> **JVM 専用 (インタプリタとコンパイル済み `.class`)。** 連携で得られる値はホストオブジェクトへの不透明な参照であるため、本物の JVM が必要です。動作するのは **JVM 上のインタプリタ** (`java -jar rontolisp.jar program.lisp`) と **JVM コンパイル済みプログラム** (`-o Prog.class` でコンパイルし `java Prog` で実行) です — コンパイラが解決した呼び出しは生成クラスの中の直接呼び出しに、`java:reify` と `java:proxy` はそのために生成したクラスになり、実行時解決に回る呼び出しのためにだけ、コンパイラは小さなリフレクションブリッジを生成クラスの隣 (`Prog$JavaBridge.class`、`-o prog.jar` ではその中のエントリー) に書き出します。そのときプログラムの実行にはそれがクラスパス上に必要です (必要な JRE は [Java リリースやクラスパスを指定したコンパイル](#compiling-against-a-java-release-or-a-class-path) を参照)。WASM バックエンドはホスト参照を表現できないため、`java:` を `.wasm` にコンパイルすると従来どおり `Cannot compile: java:...` エラーになります。GraalVM ネイティブバイナリ (`rontolisp program.lisp`) は `java:` プログラムを `.class` に**コンパイルする**ことはできますが、**インタプリタ実行**はできません。ネイティブイメージにはビルド時にリフレクション登録されたクラス・メンバーしか含まれず、rontolisp のビルドは連携用に何も登録していないため、`(java:static "java.lang.Math" "max" 3 7)` ですら `No such class` で失敗します。

## 関数

このパッケージは Common Lisp の一部ではないため、関数は `java:` 修飾子付きで参照します (または `(in-package java)` 後は修飾なし)。

| 関数 | 用途 |
|----------|---------|
| `java:new` | ホストオブジェクトの生成: `(java:new "fqcn" args...)` |
| `java:call` | インスタンスメソッドの呼び出し: `(java:call obj "method" args...)` |
| `java:static` | 静的メソッドの呼び出し: `(java:static "fqcn" "method" args...)` |
| `java:field` | 静的・インスタンスフィールドの読み取り: `(java:field class-or-obj "name")` |
| `java:proxy` | callable を 1 つ以上のインターフェースへ適合: `(java:proxy "iface"... callable)` |
| `java:subclass` | callable でクラスを継承: `(java:subclass "super" '("iface"...) '("method"...) args... callable)` |
| `java:reify` | インターフェースをメソッドごとに実装: `(java:reify "iface" "method" function ...)` |
| `java:handle` | Java に値のない Lisp の値の代理: `(java:handle value "text")` |
| `java:view` | Lisp のコレクションを読み取り専用の Java のコレクションとして代理: `(java:view value items :list)` |

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

Lisp の値も `java:call` の receiver になり、`Object` 引数に渡したときのオブジェクトとして呼ばれます。文字列は `String`、整数は `Integer`（収まらなければ `Long`）、浮動小数点数は `Double`、bignum は `BigInteger`、文字は `Character`（補助文字はそのコードポイントの `Integer`）、`t` は `Boolean.TRUE`、シンボル `|false|` は `Boolean.FALSE` です。`nil`、関数、その他のシンボル、リスト、配列、ハッシュテーブルは receiver になりません。

```lisp
(java:call "abc" "codePointAt" 0)   ; => 97
(java:call 42 "toString")           ; => "42"
```

## 値のマーシャリング

引数と結果は rontolisp と Java の間で自動変換されます。

| rontolisp | Java (入力) | Java (出力) |
|-----------|-----------|------------|
| integer | `int`/`long`/`short`/`byte`/`float`/`double` (およびそのボックス型)、`BigInteger` | `int`/`long`/.../`BigInteger` → integer |
| bignum | `BigInteger` (またはその上位型: `Number`、`Object` など) | `BigInteger` → integer |
| float | `double`/`float` (およびボックス型) | `double`/`float` → float |
| string | `String`、長さ 1 なら `char` | `String` → string |
| character | `char`/`Character` | `Character` → character |
| `t` / `nil` | `boolean` (`nil` は任意の `null` 参照にもなる) | `boolean` → `t`/`nil` (`:java-false` の後では `t`/`\|false\|`) |
| 名前が `false` のシンボル | `boolean` の false、任意の参照には `Boolean.FALSE` | `:java-false` の後では Java の false |
| `java` オブジェクト | ラップされたホストオブジェクト | その他のオブジェクト → `java` オブジェクト |
| 関数/ラムダ | 一致するインターフェースに対する `java:proxy`、`:functional` の後ではその抽象メソッドの実装 (引数に限る) | — |
| 真リスト / ベクタ (特殊化されたものも含む) | `T[]` (要素ごとに変換、プリミティブ配列も可)、または `List`/`Collection`/`Iterable` | 任意の Java 配列 → リスト |
| ハッシュテーブル | 新しい `java.util.LinkedHashMap` (`Map`、`HashMap`、`Object` など) | — |
| `java:handle` | Java からはテキストに見えるオブジェクト | 代理する値 |
| `java:view` | 要素を持つ読み取り専用の `List`・`Set`・`Map` (`List` のビューは、そのまま受け取る引数がないときに限り要素の配列) | 代理する値 |

Java の `null` (および `void` メソッド) は `nil` として返ります。Java の配列が期待される箇所に真リスト (または `make-array` で作ったランク 1 の配列。`double-float`、`single-float`、`bfloat16`、`(unsigned-byte 8|16|32)` に特殊化された配列も含む) を渡すと、要素ごとに要素型へ変換されます (`int[]` などのプリミティブ配列も含む)。`List`/`Collection`/`Iterable` が期待される箇所では `java.util.List` になり、ネストしたリストは再帰的に変換されます。逆方向では、Java の **配列** の結果は Lisp のリストになりますが、返された `java.util.List` は不透明な `java` オブジェクトのままで、そのメソッドを呼び出して操作します。

```lisp
;; in: the list becomes a Collection
(java:static "java.util.Collections" "max" (list 3 9 4))   ; => 9
```

```lisp
;; in: (1 2 3) -> int[]; out: the int[] result -> a list
(java:static "java.util.Arrays" "copyOf" (list 1 2 3) 2)   ; => (1 2)
```

bignum は、`java.math.BigInteger` (または `Number`、`Object` などその上位型) が期待される箇所に `BigInteger` として渡り、それより狭い型には渡りません。`long` にも `double` にもならないので、`double` が必要なら先に `float` で変換してください。fixnum も、それを受け取るプリミティブのオーバーロードがなければ `BigInteger` パラメータに渡ります。逆方向では、`java.math.BigInteger` の結果は `java` オブジェクトではなく Lisp の整数になるため、`java:call` ではなく Lisp の演算で扱ってください。

```lisp
;; in: a bignum -> BigInteger; a fixnum -> BigInteger where no primitive fits
(java:call (java:new "java.math.BigDecimal" (expt 10 20) 3) "toString")   ; => "100000000000000000.000"
```

```lisp
;; in: a specialized vector converts element-wise like any vector
(java:static "java.util.Arrays" "toString"
             (make-array 2 :element-type 'double-float :initial-element 0.5d0))   ; => "[0.5, 0.5]"
```

`nil` は参照が期待される位置では常に Java の `null` です。そのため `Object` 引数に `Boolean.FALSE` を渡すには、Java が false を綴るとおりのシンボル `'|false|` を使います。コールバックが `boolean`・`Boolean` の結果として返すのもこのシンボルです。ハッシュテーブルは挿入順のエントリを持つ新しい `java.util.LinkedHashMap` になり、各キーと値は `Object` 引数と同じく変換されます (`equalp` テーブルのキーは最初に格納した形です)。

```lisp
(let ((l (java:new "java.util.ArrayList")))
  (java:call l "add" '|false|)
  (java:call l "add" nil)
  (java:call l "toString"))   ; => "[false, null]"
```

```lisp
(let ((h (make-hash-table :test 'equal)))
  (setf (gethash "b" h) 2 (gethash "a" h) (list 1 2))
  (java:call (java:new "java.util.TreeMap" h) "toString"))   ; => "{a=[1, 2], b=2}"
```

その他のシンボル、分数、ドット対 (非真リスト)、多次元 (ランク 2 以上) の配列はマーシャリング **されません**。[`java:handle`](#handles-javahandle) で代理させることはできます。

### Java の false を受け取る: `:java-false`

Java の false は Common Lisp 唯一の偽である `nil` として返ります。`:java-false` で終わる `java:new`・`java:call`・`java:static`・`java:field` は、これを `|false|` として返します。`boolean` の結果、`Boolean.FALSE`、配列の要素のいずれもです。マーカーは引数の後ろに置き、`:functional` との前後は問いません。

```lisp
(let ((l (java:new "java.util.ArrayList")))
  (java:call l "add" '|false|)
  (list (java:call l "get" 0) (java:call l "get" 0 :java-false)))   ; => (NIL |false|)
```

これで終わる `java:proxy`・`java:reify`・`java:subclass` は、関数に Java の false を `|false|` として渡します。これで終わる呼び出しでインターフェースが期待される箇所に渡した関数も同様です。両方のマーカーで終わる呼び出しで `java.util.Comparator` が期待される箇所に渡した関数は、Clojure の `AFunction.compare` が関数を読むとおりに `compare` を返します。`t` は -1、`|false|` は引数 2 つを入れ替えた呼び出しが真なら 1 でなければ 0、浮動小数点数と分数は切り捨て、整数は下位 32 ビットです。`nil` は `AFunction.compare` と同じ `NullPointerException` を、それ以外の値は `ClassCastException` を投げ、呼び出しはこれをメソッドの失敗として報告します。

```lisp
(let ((l (java:new "java.util.ArrayList")))
  (dolist (x (list 3 1 2)) (java:call l "add" x))
  (java:call l "sort" (lambda (a b) (if (< a b) t '|false|)) :functional :java-false)
  (java:call l "toString"))   ; => "[1, 2, 3]"
```

### ハンドル: java:handle

`(java:handle value "text")` は、Java に値のない Lisp の値を代理する Java オブジェクトを作ります。Java からはテキストがその `toString` に見え、同じテキストのハンドル同士は `equals` で等しくなります。ハンドルはテキストでハッシュされテキストで順序付けられますが、`(java:handle value "text" hash "order")` の形では整数の下位 32 ビットをハッシュとし、order のテキストで順序付けられます。そのためハンドルは、その値が自分の言語でそうなるのと同じく `HashMap` のキーになり、`TreeSet` の中で整列します。Java がハンドルを返すところ (結果、配列の要素、コールバックの引数) ではどこでも、`java:` は代理する値を返します。

```lisp
(let ((m (java:new "java.util.HashMap")))
  (java:call m "put" (java:handle 'apple "apple") 1)
  (list (java:call m "toString")
        (java:call m "get" (java:handle nil "apple"))
        (java:call (java:call m "keySet") "toArray")))   ; => ("{apple=1}" 1 (APPLE))
```

完全な形は `(java:handle value text hash order class)` です。`hash` が nil のハンドルはまったく同じ値のハンドルとだけ等しくなり、このとき `text` を nil にすると `Object` と同じくクラス名とハッシュで綴られます。関数の `order` は、値と、Java がハンドルと比べるオブジェクトとを比べ、その答えの符号で順序を決めます。`order` が nil のハンドルは何とも順序付けられません。`class` は等価と順序を分けます。クラスの異なるハンドル同士は等しくならず、比べると両方のクラス名を挙げた `ClassCastException` になります。実数のハンドルは、その数の `java.lang.Number` です ([java:handle](../reference/functions/java-handle.md))。

### ビュー: java:view

`(java:view value items shape)` は、Lisp のコレクションを代理する読み取り専用の Java コレクションを作ります。要素は items を `Object` 引数と同じ規則で一度だけ変換したものです。`shape` は `:list`、`:vector` (`RandomAccess` かつ `Comparable` でもある `List`)、`:set`、`:map` (ハッシュテーブルか plist から作る) のいずれかです。Java はこれを `java.util` のインターフェースを通して読みます。`equals` と `hashCode` はそれぞれの規約どおりで、書き込みはすべて `UnsupportedOperationException` になります。`toString` は値に対する printer の答えで、Java がビューを返すところでは代理する値が返ります。

```lisp
(let* ((items (list 1 "two"))
       (v (java:view items items :list (lambda (x) (format nil "<~{~A~^ ~}>" x))))
       (l (java:new "java.util.ArrayList")))
  (java:call l "add" v)
  (list (java:call l "toString") (eq (java:call l "get" 0) items)))   ; => ("[<1 two>]" T)
```

ビューは、クラスが合う箇所にはそのまま渡ります。Java の配列が期待される箇所では `List` のビューは要素の配列になりますが、それはビューをそのまま渡す方法 (可変長引数の配列に一つの要素として詰める方法を含む) がすべて合わないときに限られます。この変換があるのは、呼び出しが返した Java の配列がここではリストになるためで、そのビューは後の呼び出しが期待する配列に戻ります。Clojure フロントエンドは、すべての値をこの形で Java に渡します。ベクタ・リスト・セット・マップは Clojure の印字どおりに綴られるビューとして、キーワード・シンボル・分数は Clojure の `Keyword`・`Symbol`・`Ratio` と同じくハッシュし順序付けるハンドルとして、それ以外の値は自分自身とだけ等しいハンドルとして渡します ([java:view](../reference/functions/java-view.md))。

`java` オブジェクトが `eq`・`eql` になるのは自分自身とだけです。2 回の呼び出しが返した同じオブジェクトは `eq` ですが、`equals` が真になる別々のオブジェクトは `eq` ではありません。`equal` と `equalp` はオブジェクトの `equals` で比較します。そのため `eq`・`eql` のハッシュテーブルは `java` オブジェクトを同一性でキーにし (格納後に変更したキーも見つかります)、`equal`・`equalp` のテーブルは `equals` と `hashCode` でキーにします。

```lisp
(let ((a (java:new "java.io.File" "x"))
      (b (java:new "java.io.File" "x")))
  (list (eq a b) (eql a b) (equal a b) (eq a a)))   ; => (NIL NIL T T)
```

右辺が Lisp の値のとき、`equal` はその値を Java メソッドの `Object` 引数が受け取る形 (文字列は `String`、文字は `Character`、`nil` は `null`) にしてオブジェクトの `equals` に渡します。シンボル、リスト、ベクタ、分数はどの `java` オブジェクトとも `equal` になりません。左辺が Lisp の値なら `java` オブジェクトと `equal` になることはありません。左オペランドに尋ねる Clojure の `=` と同じです。

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

関数は、関数型インターフェース (抽象メソッドが 1 つ) に対してそれ以外のインターフェースより低いコストになります。そのため Java のラムダの変換先と同じく、`TreeSet(Collection)` より `TreeSet(Comparator)` が選ばれます。

```lisp
(let ((s (java:new "java.util.TreeSet" (lambda (a b) (- b a)) :functional)))
  (java:call s "add" 1)
  (java:call s "add" 2)
  (java:call s "toString"))   ; => "[2, 1]"
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
- インターフェース名がリテラルの `(java:reify "I" ...)` と `(java:proxy "I" ...)` は、`I` を実装し、プログラムが名前で指せる型はほかに実装しないクラスのオブジェクトを作る。それに対する呼び出しは `I` のメソッドの中から解決され、それを引数として渡す呼び出しも解決される。`let` 変数はこの型を保ち、その表記が `(java:object "I" :exact)` である。インターフェースをちょうどクラスとするオブジェクトは存在しないので、インターフェースに対する `:exact` はこの意味になる。リテラルのインターフェースを複数並べた `(java:proxy "I" "J" ...)` はそのそれぞれを実装する。それを渡す呼び出しは解決され、それに対する呼び出しは実行時にそのクラスで解決され、その型を表す指定子はない

既知のクラスがインターフェースである値に対する呼び出しは、そのインターフェースが宣言していない `Object` の public メソッド（`toString`、`getClass` など）にも解決されます。Java の `list.toString()` と同じです。

文字列・浮動小数点数・文字・bignum・`t` とわかっている値に対する呼び出しは、それが呼ばれるクラス（`String`、`Double` など）のメソッドの中で解決されます。整数のボックスは大きさで決まるため、整数に対する呼び出しは実行時に解決されます。

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

インタプリタは実行中の JDK とプログラムのクラスパス ([Java ライブラリ](#java-libraries)) に対して解決します。JVM コンパイラは代わりにクラスファイルを読みます。JDK の `lib/ct.sym` (実行中の JDK のもの、なければ `JAVA_HOME` のもの、なければ `PATH` 上の `java` のもの) から、その JDK が持つ最新のリリース、または `--java-release N` のリリースを読み、続いてクラスパスを探します。コンパイル済みクラスはコンパイル時に選ばれたメソッドを呼ぶので、そのリリース向けに刻印されます (クラスバージョン 44 + N、最低でも Java 17 の 61)。そのリリースより古い JRE はクラスの読み込みを拒否します。コンパイル時に見えないクラスを名指す呼び出しは実行時に解決され、その呼び出しが使うリフレクションブリッジには、rontolisp をビルドした JRE と同等以上に新しい JRE が必要です。

```console
$ rontolisp app.lisp -o app.jar --java-release 21 --java-classpath lib/guava.jar
```

### リフレクションなしのコンパイル

`--java-static` は、リフレクションを必要とする呼び出しをすべてコンパイルエラーにします。実行時解決に回る呼び出しと、インターフェース名を実行時に与える、またはコンパイル時にインターフェースが見つからない `java:reify` と `java:proxy` (`java.lang.reflect.Proxy` になる) が該当し、コンパイルはそれらを一度にすべて列挙します。`java:reify`、リテラルのインターフェースに対する `java:proxy`、インターフェースが期待される箇所に渡した関数は、コンパイル時に生成するクラスになるので、リフレクションを必要としません。種別が実行時にしか分からない引数も、そこでインターフェースを期待するオーバーロード (`String.join(CharSequence, Iterable)` など) に対しては関数でありうるので同じ扱いになります。コンパイルが通ったものはリフレクションを含まないので、GraalVM の `native-image` はその jar をリーチャビリティメタデータなし (`reflect-config.json` もエージェント実行も不要) で実行ファイルにビルドできます。

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

## java:reify によるインターフェースの実装

`java:reify` はホストインターフェースをメソッドごとに実装します。各メソッド名の後ろに、そのメソッドを実装する関数を置き、関数はメソッドの引数で呼ばれます。作ったオブジェクトはそのインターフェースが期待される箇所ならどこにでも渡せ、Java 側はほかの実装と同じように呼び出します。

```lisp
(let ((support (java:new "java.beans.PropertyChangeSupport" "bean"))
      (seen nil))
  (let ((listener (java:reify "java.beans.PropertyChangeListener" "propertyChange"
                    (lambda (e) (push (java:call e "getNewValue") seen)))))
    (java:call support "addPropertyChangeListener" listener)
    (java:call support "firePropertyChange" "size" 1 2)
    (java:call support "removePropertyChangeListener" listener)
    (java:call support "firePropertyChange" "size" 2 3))
  seen)
; => (2)
```

メソッドはフォームの実行前に選ばれ、その規則はインタプリタとコンパイル済みプログラムで共通です。

- 名前は 1 つのメソッドを指す。複数のメソッドが共有する名前には、`java:call` の名前と同じくパラメータ型のタグを付ける (`"append(char)"`)。複数のメソッドに一致する名前や、どのメソッドにも一致しない名前はエラーになる
- どの名前も指さない抽象メソッドは、呼ぶと `UnsupportedOperationException` を投げる。デフォルトメソッドはインターフェースの本体を保つ。`toString`、`equals`、`hashCode` も指定でき、指定しなければ `#<java-reify I>` と同一性比較になる
- 関数の値は引数と同じ規則でメソッドの戻り型へ変換される。ただし関数は戻る方向ではプロキシにしないので、インターフェースが期待される戻り値には `java:reify` か `java:proxy` のオブジェクトを返す

コンパイル済みプログラムは、名前がリテラル文字列の `java:reify` をそれぞれ専用に生成したクラス (`Prog$Reify0.class`) で実装するので、リフレクションを必要としません。[リフレクションなしのコンパイル](#compiling-without-reflection)を参照してください。[リファレンスページ](../reference/functions/java-reify.md)に例がさらにあります。

## java:proxy によるコールバック

`java:proxy` は rontolisp の callable を背後に持つホストインターフェースのインスタンスを作ります。callable は各インターフェースメソッドに対して `(callable "method-name" arg...)` の形で適用されるため、1 つのラムダでインターフェース全体を実装し、メソッド名で振り分けることができます。戻り値はメソッドの戻り型へマーシャリングされます (`void` メソッドは無視し、返した関数はプロキシにしません)。

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

引数の後ろを `:functional` で終えた `java:new`・`java:call`・`java:static` (`java:subclass` ではコンストラクタ引数について、callable の後ろ) は、関数を Java がラムダを変換するのと同じ形で変換します。インターフェースの各抽象メソッドはメソッドの引数だけで関数を呼び、default メソッドは本体を保ちます。Clojure フロントエンドは呼び出しをこれで終えるので、Clojure の `fn` はメソッド名を受け取りません。

```lisp
(let ((lst (java:new "java.util.ArrayList")))
  (dolist (x (list 3 1 2)) (java:call lst "add" x))
  (java:static "java.util.Collections" "sort" lst (lambda (a b) (- b a)) :functional)
  (java:call lst "toString"))
; => "[3, 2, 1]"
```

## java:subclass によるクラスの proxy

`java:subclass` は rontolisp の callable を背後に持つホストクラスのインスタンスを作ります。`java.lang.reflect.Proxy` はインターフェースしか実装できないため、`java:proxy` にはできないことです。このフォームはスーパークラス、追加のインターフェース、オーバーライドするメソッド、コンストラクタ引数を指定します。

```lisp
(java:subclass "java.io.File" '() '("lastModified") "recent"
  (lambda (this method &rest args) 42))
```

callable は、名前を挙げた各メソッドに対して `(callable this "method-name" arg...)` の形で適用されます。最初が `this`、次がメソッド名です。コンストラクタ引数は、共有のオーバーロード規則でスーパークラスのコンストラクタを選びます。名前を挙げたメソッドは本体を実行します（`toString`/`equals`/`hashCode` を含みます）。名前を挙げなかったメソッドは、クラスに実装があれば継承し、実装がなければ呼ばれたときにメソッド名とともに `UnsupportedOperationException` を送出します。`proxy-super`（Clojure で書く場合）は、生成した `super$` アクセサーを通常のメソッドとして呼ぶことでスーパークラスの実装に届きます。

```lisp
(java:call (java:subclass "java.io.File" '() '("toString") "x"
             (lambda (this method &rest args) "over!"))
           "super$toString$0")
; => "x"
```

名前がリテラル文字列である `java:subclass` はコンパイル時に生成するクラスになるため、構築は `--java-static` でコンパイルできます。実行時まで残るもの（実行時に計算する名前や、コンパイル時に見えないクラス。プロジェクトのクラスには `--java-classpath` が要ります）は名前を上げて拒否されます。インタープリターは実行時に解決します。さらなる例は[リファレンスページ](../reference/functions/java-subclass.md)にあります。

## エラーと非局所脱出

Java のメンバが投げた例外は `java:java-exception` として通知されます。これはメンバと例外を示す `simple-error` で、例外そのものを保持しており、`java:java-exception-cause` がそれを返します。

```lisp
(handler-case (java:static "java.lang.Integer" "parseInt" "x")
  (error (e) (format nil "~a" e)))
; => "error calling java.lang.Integer.parseInt: java.lang.NumberFormatException: For input string: \"x\""
```

```lisp
(handler-case (java:static "java.lang.Integer" "parseInt" "x")
  (java:java-exception (e)
    (java:call (java:java-exception-cause e) "getMessage")))
; => "For input string: \"x\""
```

`java:java-exception` を Java に渡すと、メンバの引数としても、実行前にクラスの分からない `java:call` のレシーバとしても、保持している例外として扱われます。

```lisp
(handler-case (java:static "java.lang.Integer" "parseInt" "x")
  (java:java-exception (e)
    (java:call (java:call (java:new "java.lang.RuntimeException" "wrapped" e) "getCause")
               "getMessage")))
; => "For input string: \"x\""
```

Java からコールバックとして呼ばれた rontolisp の関数が通知したコンディションや、その関数から抜ける `return-from`・`throw`・`go` は、Lisp のフレームを抜けるときと同じく途中の Java のフレームをそのまま伝播し、Java を呼び出したコードに到達します。

```lisp
(block found
  (java:call (java:static "java.util.List" "of" 1 2 3) "forEach"
             (lambda (method x) (when (= x 2) (return-from found x))))
  nil)
; => 2
```

```lisp
(handler-case
    (java:call (java:static "java.util.List" "of" 1) "forEach"
               (lambda (method x) (error "bad element ~a" x)))
  (error (e) (format nil "~a" e)))
; => "bad element 1"
```

途中の Java のコードにとってこれは通常の例外であり、到達するのはそのコードが伝播させたものだけです。捕捉して握りつぶされたものは到達せず、ラップされたものや別スレッドで投げ直されたものは、その Java 呼び出し自体の失敗として到達します (`FutureTask.get` は `ExecutionException` でラップします)。

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

## Java ライブラリ

プログラムの Java クラスパスは、`java:` 呼び出しが JDK の外で使うライブラリを保持します。`--java-classpath` はディレクトリと jar を `java -cp` と同じ区切りで指定し、`--java-dep` はライブラリを Maven 座標 (`groupId:artifactId:version`、繰り返し指定可) で、その依存ごと指定します。インタプリタはクラスパスからクラスを読み込み、JVM コンパイルはクラスパスに対して呼び出しを解決し、Clojure プログラムのホスト形式は JDK のクラスと同じようにクラスパスのクラスを参照します。

```console
$ rontolisp app.lisp --java-dep com.google.guava:guava:33.4.0-jre
$ rontolisp app.lisp -o app.jar --java-dep com.google.guava:guava:33.4.0-jre
$ java -jar app.jar
```

`--java-dep` は Maven がプロジェクトの依存を解決するのと同じ方法で解決します。同じライブラリの 2 つのバージョンが出会うと、要求した座標に近い方が勝ち、jar は `--java-classpath` のエントリーの後に Maven のクラスパス順で並びます。取得元は Maven Central で、`mvn` と同じローカルリポジトリ (`~/.m2/repository`、または `settings.xml` の `localRepository`) を経由します。SNAPSHOT、`LATEST`、`RELEASE`、バージョン範囲は、指定したものも依存の POM にあるものも、Maven と同じく Central の `maven-metadata.xml` で解決します。そのメタデータはローカルリポジトリに保存し、Central に問い合わせ直すのは 1 日に 1 回です。Central になかったファイルも同じです。Maven と同じく Central は SNAPSHOT を配布しない扱いで、SNAPSHOT は `--java-repository` で指定したリポジトリだけから探します。

Clojars、社内リポジトリ、`file:` ディレクトリなど、Central にないライブラリは `--java-repository [ID=]URL` (繰り返し指定可。`https:`、`http:`、`file:`) で指定します。これらのリポジトリは Central の後に、指定した順で検索します。`ID` (省略時は `java-repository-N`) は `settings.xml` が照合する名前で、`<server>` がその認証情報を与え、`mirrorOf` がこの ID を指す `<mirror>` は URL を置き換えます。ID を `central` にすると、リポジトリを追加せず Central の URL を置き換えます。

```console
$ rontolisp app.lisp --java-dep clj-http:clj-http:3.12.3 \
    --java-repository clojars=https://repo.clojars.org/
```

`settings.xml` は `mvn` と同じく効きます。読むのは `~/.m2/settings.xml` で、`MAVEN_HOME` が設定されていれば `$MAVEN_HOME/conf/settings.xml` の上に重ねます。`offline` に従い、Central を覆うミラーがあれば Central の代わりにそのミラーへ問い合わせ (`blocked` のミラーなら失敗します)、プロキシがあればそれを経由します。問い合わせ先のリポジトリの `<server>` からは、Basic 認証の認証情報、`httpHeaders`、タイムアウトを使います。`mvn --encrypt-password` で暗号化したパスワードは、`~/.m2/settings-security.xml` のマスターパスワードで復号します。

有効な `settings.xml` のプロファイル（`<activeProfiles>` に挙げたもの、または `<activation>` が成り立つもの。グローバルとユーザーの両ファイルを合わせます）の `<repositories>` は、`mvn` と同じ順で検索します。Central と `--java-repository` で指定したものより前に置き、後に定義したプロファイルを先に、1 つのプロファイル内では記述順です。それらと同じ id のプロファイルのリポジトリは、そのリポジトリを置き換えます。

Clojure プログラムの `deps.edn` の依存のうちクラスを含むものは、これらの後にクラスパスへ加わります ([プロジェクト: deps.edn](../clojure/semantics.md#projects-depsedn))。

出力ごとに持ち運ぶもの:

- プログラム jar (`-o app.jar`) はクラスパスを隣の `app-lib/` にコピーし、マニフェストの `Class-Path` でそのコピーを指します。そのため `java -jar app.jar` と `native-image -jar app.jar` がそれを見つけます。配布するときは jar と `app-lib/` を一緒に配ってください。
- war (`-o app.war`) は jar を `WEB-INF/lib/` に、ディレクトリのファイルを `WEB-INF/classes/` に格納します。
- クラス (`-o Prog.class`) は何も持ち運びません。`java -cp .:lib/guava.jar Prog` のようにクラスパスを付けて実行してください。
- ライブラリ jar (`--no-main`) もクラスパスを持ち運びません。その pom (`--maven-coordinates`、`--emit-pom`) が `--java-dep` の座標を依存として列挙し、利用側の Maven がそれを解決します。`--java-classpath` のエントリーは利用側が用意します。

GraalVM ネイティブバイナリは実行時にクラスを読み込めないため、そこではクラスパスはコンパイルが呼び出しを解決する対象と、その出力が持ち運ぶものにだけ届きます。

## ネイティブイメージ

コンパイル済みの `java:` プログラムは GraalVM ネイティブイメージにビルドできます。すべての呼び出しが実行前に解決されるプログラムには何も要りません。`--java-static` でコンパイルし ([リフレクションなしのコンパイル](#compiling-without-reflection))、その jar をそのままビルドしてください。そのプログラムの `java:reify` と `java:proxy` のオブジェクト、およびインターフェースが期待される箇所に渡す関数は、コンパイル時に生成するクラスなので、これらにも何も要りません。実行時解決に回る呼び出しはリフレクションを使うので到達可能性メタデータが必要で、トレーシングエージェントが実行から記録します。

```bash
rontolisp prog.lisp -o prog.jar
java -agentlib:native-image-agent=config-output-dir=config -jar prog.jar
native-image -jar prog.jar -H:ConfigurationFileDirectories=config
```

メタデータがカバーするのはトレースした実行が行った呼び出しだけです。その実行が選ばなかったオーバーロードを選ぶ呼び出しは、イメージ内で `MissingReflectionRegistrationError` になります。たとえばクラスの分からない `sb` に対する `(java:call sb "append" x)` に、整数だけを渡した実行の後で浮動小数点数を渡した場合です。プログラムが使うすべての呼び出しの形を通る実行でトレースするか、型を宣言して呼び出しを解決させてください。

## 制限

- **JVM 専用**。インタプリタ (`java -jar rontolisp.jar`) と JVM コンパイル済みクラス (`java Prog`) で動作します。WASM バックエンドでは動作せず、連携クラスのリフレクションメタデータを持たない GraalVM ネイティブバイナリでのインタプリタ実行もできません (ネイティブバイナリで `java:` プログラムを `.class` に*コンパイルする*ことは可能です)。
- コンパイル済みクラスでは `java:` の関数は呼び出し位置でのみ使えます。第一級の関数値を持たないため、`#'java:call` や `(funcall 'java:new ...)` はコンパイルエラーになります (代わりに自前の `defun` でラップしてください)。埋め込み `eval` ランタイムもこれらを認識しません。また `java:` を使うコンパイル済みプログラムの実行には、呼び出しを解決したリリースの JRE が必要で、実行時解決に回る呼び出しを含むものには、rontolisp をビルドした JRE と同等以上に新しい JRE が必要です。
- `|false|` 以外のシンボル、ドット対 (非真リスト)、多次元 (ランク 2 以上) の配列はマーシャリングされません。代わりに `java:new`/`java:call` で構築した Java コレクションか、`java:handle` または `java:view` として渡してください。
- 返された `java.util.List` は (Java 配列と異なり) 不透明な `java` オブジェクトのままです。同一性と可変性が保たれるため、リスト関数ではなく `java:call` (`"get"`、`"size"` など) で読み取ってください。
- オーバーロード解決は引数コストによるもので、Java の完全な型推論規則ではありません。曖昧な呼び出しは曖昧性エラーを出さず、最小コスト (次に最小シグネチャ) の候補に解決されます。パラメータタグでオーバーロードを明示できます。
- これは完全なホストリフレクションブリッジであり任意の Java コードを実行できます。`java:` を使うプログラムは他の JVM プログラムと同じ信頼度で扱ってください。
