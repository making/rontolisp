# Java 言語のサブセット（実験的）

**実験的。** rontolisp は Java 言語のサブセットを読み、Common Lisp と同じコアフォームに
変換します。そのため `.java` のプログラムはどのバックエンドでも動きます。サブセットであることは
設計上の選択で、ここにあるものは互換性の保証ではありません。サブセットにない構文は、
プログラムを実行する前に、構文の名前と位置を示して拒否します。フロントエンドと JDK のクラスは
公開されている仕様から rontolisp のために書いたものです。JDK のコードは読み込まず、同梱せず、
コンパイルしたプログラムにも含めません。

`.java` のファイルはこのサブセットとして読まれます。それ以外のファイルと `-e` では
`--source-language java` で指定します。プログラムは、開始するファイルと、そのファイルが名前を
挙げるソースツリー内のファイル、それにソースパス上のライブラリのうちプログラムが到達する部分です
（[サードパーティのライブラリを使う](#using-a-third-party-library)）。

```bash
rontolisp Hello.java                                # interpreter
rontolisp Hello.java -o Hello.class && java Hello   # JVM
rontolisp Hello.java -o hello.wasm && wasmtime run hello.wasm
rontolisp Hello.java -o hello-c.wasm --component && wasmtime run hello-c.wasm
rontolisp -e 'void main() { IO.println(1 + 2); }' --source-language java
```

`--no-gc` は拒否されます。そのバックエンドには構造体も文字列もクロージャもないからです。
プログラムを与えずに `--source-language java` を指定すると REPL が起動します
（[プロンプトで](#at-the-prompt)）。

[`examples/java/`](https://github.com/making/rontolisp/tree/develop/examples/java) に、
複数のパッケージからなるプログラムとその期待される出力、HTTP サーバーとその
クライアント、小さな httpbin、およびソース jar から読んだライブラリでフォームを検証する
プログラムがあります。

```java
interface Shape {
    double area();
}

class Circle implements Shape {
    final double r;

    Circle(double r) {
        this.r = r;
    }

    public double area() {
        return Math.PI * r * r;
    }

    public String toString() {
        return "circle " + r;
    }
}

class Square implements Shape {
    final double side;

    Square(double side) {
        this.side = side;
    }

    public double area() {
        return side * side;
    }
}

void main() {
    List<Shape> shapes = new ArrayList<>();
    shapes.add(new Circle(1.5));
    shapes.add(new Square(2));
    double total = 0;
    for (Shape shape : shapes) {
        total += shape.area();
    }
    IO.println(shapes.get(0) + ", " + shapes.size() + " shapes, total " + total);
    IO.println(Integer.MAX_VALUE + 1);
}
```

```
circle 1.5, 2 shapes, total 11.068583470577035
-2147483648
```

## プログラムの開始位置

ファイルの最初のクラスに `main` があればそのクラスから、なければファイルと同じ名前のクラスから
始まります。`main()` より `main(String[])` が優先されます。インスタンスメソッドの `main` は、
引数のないコンストラクタで作ったインスタンスに対して呼ばれます。引数の配列は空です。
トップレベルにメソッドやフィールドを書いたファイルはコンパクトソースファイルです。それらは
1 つのクラスのメンバーになり、[使える JDK のクラス](classes.md)はすべて import なしで使えます。同じファイルに
書いたクラスも、そのクラスのメンバーになります。`static` を付けたクラス、レコード、列挙型、
インターフェース以外は内部クラスです。

## 複数ファイルのプログラム

プログラムが名前を挙げたクラスを、それまでに読んだどのファイルも宣言していなければ、
ソースツリーのルートの下で、そのクラスのパッケージと名前が示すファイルから読みます。ルートは、
開始するファイルのパッケージが示すディレクトリの上です。`package app;` を宣言する
`app/Main.java` では、`util.Strings` は `util/Strings.java`、パッケージ `app` の `Helper` は
`app/Helper.java` です。読んだファイルのクラスはすべてプログラムに加わり、プログラム全体を
まとめてコンパイルします。開始するファイルの上のディレクトリは、そのパッケージと一致して
いなければなりません。Common Lisp から `(load "app/Main.java")` すると、
`rontolisp app/Main.java` と同じようにプログラムを実行します（[言語をまたぐ呼び出し](#across-languages)）。

```console
$ rontolisp app/Main.java
$ rontolisp app/Main.java -o main.wasm
```

`import` には、クラス、オンデマンドのパッケージ、`module java.base`（`java.sql` を除く
使える JDK のクラスすべて）、`module java.sql` を指定できます。オンデマンドのパッケージは
モジュールのインポートより優先します。同じ種類の 2 つのパッケージが同じ名前を持ち込むと、
その名前は拒否されます（`reference to Date is ambiguous`）。`static` を付けると、クラスの static メンバー 1 つ、またはオンデマンドで
そのすべてを指定できます。`public` でないクラスとアクセス修飾子のないメンバーには、同じ
パッケージからだけアクセスできます。`protected` のメンバーには、同じパッケージと、そのクラスの
サブクラスからアクセスできます。

## サードパーティのライブラリを使う

プログラムが名前を挙げたクラスをソースツリーが宣言していなければ、次にプログラムの
**ソースパス**から読みます。ソースパスは、使うライブラリのルートをソースのまま並べたものです。
ライブラリは、プログラムが到達した範囲だけを、どのバックエンドでもプログラムと一緒に
コンパイルします。出力の横に置くものはなく、`.wasm` はライブラリのうち使う部分を含みます。

```console
$ rontolisp app/Main.java --java-source-path libs/geo-sources.jar:other/src/main/java
$ rontolisp app/Main.java --java-source-dep org.example:geo:1.2.0 -o main.wasm
```

- `--java-source-path` には、ディレクトリと jar をクラスパスと同じ区切りで並べます。クラス
  `geo.Table` は、最初にそれを持つルートの下の `geo/Table.java` です。
- `--java-source-dep` には Maven 座標を指定します。繰り返し指定できます。そのライブラリの
  ソース jar（`geo-1.2.0-sources.jar`）と、それが実行時に依存するもののソース jar が、
  Maven Central から `~/.m2/repository` を経由してソースパスに加わります。
  `--java-repository` でリポジトリを追加できます。指定したライブラリがソース jar を公開して
  いなければ拒否します。依存先がソース jar を公開していない場合は警告にとどめ、そのクラスは
  無いものとして扱います。

[`examples/java/yavi`](https://github.com/making/rontolisp/tree/develop/examples/java/yavi)
には、YAVI（`--java-source-dep am.ik.yavi:yavi:0.18.0`）を使うプログラムが 2 つあり、どの
バックエンドでも動きます。1 つはレコードを検証し、もう 1 つはスキーマでマップからレコードを
読み取ります。

これは `--java-classpath` や `--java-dep` とは別のものです。そちらは Common Lisp や Clojure の
`java:` 呼び出しのためにホストが実行するクラスファイルで、Java サブセットのプログラムは
それを呼びません。

ライブラリが動くかどうかは、プログラムがそのどこに到達するかで決まります。コンパイルするのは、
呼び出したメソッド、インスタンスを作ったクラス、使った static 初期化だけで、それぞれに
プログラム自身のコードと同じ検査をします。したがって、プログラムが使う部分がサブセットと
[使える JDK のクラス](classes.md)の範囲に収まっていれば、そのライブラリは動きます。
プログラムが到達した先にサブセットに無いものがあれば、ライブラリ内のその位置のエラーになり、
プログラムからそこへ至る経路を添えます。

```
geo-sources.jar!/geo/Table.java:42:54: the class java.util.Currency is not supported by the experimental Java subset yet
  reached through geo.Table.column(String,geo.Table.ToMoney)
```

ライブラリが宣言の中でだけ名前を挙げるクラス（プログラムが呼ばないメソッドの引数の型など）は
素通りします。ライブラリ内のアノテーションは、その型がソースパスに無ければ読み飛ばします。
コンパイラがクラスファイルをその型なしで読むのと同じで、アノテーションだけの依存は無くても
かまいません。ライブラリが別のクラスの名前のファイルに宣言したクラスは、そのパッケージの
どのファイルにあっても、クラスファイルがある場合と同じように見つかります。ライブラリは全体を
まとめてコンパイルされているからです。プログラム自身のソースツリーでは、`java` ランチャーと
同じく、そのファイルを読んだ後で見つかります。同じ名前のファイルがプログラムのソースツリーと
ソースパスの両方にあれば、プログラムのものを使います。

### リソース

クラスやクラスローダーの `getResourceAsStream` は、プログラムのソースツリーか、ソースパス上の
ライブラリのファイルを読みます。ファイルはコンパイル時に見つけ、その内容を出力に含めます。
そのため `.wasm` はファイルシステムなしで読めます。名前は定数でなければなりません。実行時に
組み立てた名前は拒否されます。

```
InputStream in = Main.class.getResourceAsStream("data.txt");   // app/Main.java と同じ場所の app/data.txt
InputStream top = Main.class.getResourceAsStream("/top.txt");  // ツリーの最上位の top.txt
```

クラスローダーは `java` ランチャーのものと同じです。プログラムのクラスはプログラムのローダーを
持ち、ソースツリーを探してから、ソースパスの各ルートを順に探します。ソースパス上のライブラリの
クラスはシステムローダー（`ClassLoader.getSystemClassLoader()`）を持ち、ルートだけを探します。
JDK のクラスはどちらも見つけません。ソースツリーでは名前を書かれたとおりに照合します。
ランチャーと同じく `..` や `.` は解決しません。ソースパスのディレクトリでは、クラスパスの
ディレクトリと同じく解決します。jar ではエントリ名と完全に一致する必要があります。
プレイグラウンドでは、アップロードしたファイルがプログラムのソースツリーです。

## プロンプトで

プログラムを与えずに `--source-language java` を指定したときと、プレイグラウンドで Java を
選んだときは、バッファを 1 つずつ読みます。バッファに書けるのは、文、式、変数、メソッド、
クラス、import です。文の `;` は省略できます。式の値は `$1`、`$2`、... という生成された名前で
表示され、後のバッファはその名前で式の型のまま値を読めます。変数の値はその変数の名前で
表示されます。文字列と `char` はリテラルの形で、配列は型と要素で表示されます。

```console
java> int twice(int n) { return 2 * n; }
java> int quad(int n) { return twice(twice(n)); }
java> quad(1)
$1 ==> 4
java> int twice(int n) { return n + 1; }
java> quad(1)
$2 ==> 3
java> var names = new String[] { "a", "b" }
names ==> String[2] { "a", "b" }
java> 1 / 0
Error: Exception java.lang.ArithmeticException: / by zero
```

宣言は、同じ名前（メソッドは名前と引数の型）の以前の宣言を置き換えます。以前に宣言したものは
新しい宣言を呼びます。後から加えたメソッド、オーバーライド、サブクラスにも、すべての呼び出しが
届きます。クラスの static フィールドは、そのクラスを宣言し直すまで値を保ちます。宣言し直すと、
そのクラスを型に含む変数は `null` になります。セッションはソースツリーのファイルを読みません。
[ソースパス](#using-a-third-party-library)上のライブラリのクラスは、プログラムと同じように読みます。
プレイグラウンドにはソースパスがありません。その代わり、アップロードしたファイルが、
プログラムとセッションのソースツリーになります。プログラムが名前を挙げたクラスは、その名前が
示すアップロードから読みます。`Place` は `Place.java`、`geo.Place` は `geo/Place.java` です。
そのクラスは、プログラム自身のクラスと同じように検査します。ページはファイルを名前だけで
アップロードするので、アップロードしたクラスは無名パッケージに属します。

## 言語をまたぐ呼び出し

Common Lisp のプログラムは `.java` ファイルを load できます。`main` のあるファイルは `main` を
実行します。`main` のないファイルはクラスを定義するだけで、何も実行しません。static メソッドは、
クラス名、メソッド名、引数の型からなる名前の関数です。たとえば `|Geometry.area(int)|`、
パッケージ内なら `|app.Geometry.area(int)|`、コンストラクタなら `|Point.new(int,int)|` です。
Java プログラムの `native` な static メソッドは、名前の各大文字の前にハイフンを入れた名前の
Common Lisp 関数です。`stringUpcase` は `string-upcase` で、プログラムを load したパッケージで
読まれます。

```console
$ cat Geometry.java
record Point(int x, int y) { }

class Geometry {
    static native int square(int n);
    static native String stringUpcase(String s);

    static int norm2(Point p) {
        return square(p.x()) + square(p.y());
    }

    static String shout(String s) {
        return stringUpcase(s) + "!";
    }
}
$ cat main.lisp
(defun square (n) (* n n))
(load "Geometry.java")
(print (|Geometry.norm2(Point)| (|Point.new(int,int)| 3 4)))
(print (|Geometry.shout(String)| "hi"))
$ rontolisp main.lisp
25
"HI!"
```

値は変換しません。例外は 2 つだけで、`char` は文字として渡り、`boolean` は `T` か `NIL` として
戻ります。数値は数値、文字列は文字列、`null` は `NIL`、プログラムのオブジェクトは構造体、
`Object[]` はベクタです。Common Lisp のリストは `List` ではありません。Java のメソッドには
ベクタを渡します。

## ツール

`rontolisp test` は `.java` ファイルを拒否します。このサブセットにはテストフレームワークがなく、
プログラムの終了ステータスがそのまま結果になるからです。`rontolisp format` も拒否します。
このコマンドが整形するのは S 式です。

## Maven プロジェクト

`rontolisp-maven-plugin` の `wasm` ゴールは、Maven プロジェクトの `src/main/java` にある
プログラムを、メインクラスから始めて `package` で WebAssembly にコンパイルします。同じソースは
引き続き javac がコンパイルし、そのテストは surefire が実行するので、プログラムは JDK でも
動きます。

```xml
<plugin>
    <groupId>am.ik.rontolisp</groupId>
    <artifactId>rontolisp-maven-plugin</artifactId>
    <version>VERSION</version>
    <configuration>
        <mainClass>app.Main</mainClass>
    </configuration>
    <executions>
        <execution>
            <id>preview1</id>
            <goals><goal>wasm</goal></goals>
        </execution>
        <execution>
            <id>component</id>
            <goals><goal>wasm</goal></goals>
            <configuration>
                <component>true</component>
                <outputFile>${project.build.directory}/app-component.wasm</outputFile>
            </configuration>
        </execution>
    </executions>
</plugin>
```

```console
$ mvn package
$ wasmtime run target/app-1.0.0.wasm
$ wasmtime run target/app-component.wasm
```

- プログラムは `rontolisp src/main/java/app/Main.java -o target/app-1.0.0.wasm` がコンパイル
  するものと同じです。メインクラスのファイルと、それが名前を挙げるツリー内のファイルです
  （[複数ファイルのプログラム](#a-program-of-several-files)）。
- 出力は `outputFile` で別の名前を指定しない限り `target/<finalName>.wasm` です。
  `<native>true</native>` はネイティブ実行ファイル `target/<finalName>` を書き出します。
- パラメータはコマンドラインのフラグをキャメルケースにしたものです: `component`、`noWasi`、
  `optimize`、`simd`、`hostRandom`、`hostFetch`、`reentrant`、`hostBoundary`、
  `reportLocations`、`emitWit`、`emitJsGlue`、`native`、`nativeTarget`、`nativeCpu`、
  `dynamic`、`noPrune`、`warningsAsErrors`、`javaSourcePath`。`skip`
  （`-Drontolisp.skip=true`）でゴールを無効にします。
- フロントエンドが読むのはソースで、クラスファイルではありません（[サードパーティのライブラリを使う](#using-a-third-party-library)）。
  スコープが `compile`、`provided`、`system` の各依存について、その sources jar をプロジェクトの
  リポジトリから取得し、ソースパスに加えます。順序は `<javaSourcePath>` に並べたディレクトリと
  jar の後です。sources jar のない依存は警告になり、プログラムがそのクラスを名指しすると、
  ゴールはその名前と位置を示して失敗します。そうした jar の `@Nullable` なら
  `.../app/Main.java:7:9: cannot find symbol: class Nullable` です。
- 前回のビルドより新しいファイル（ツリーとソースパス上のディレクトリの `.java` ファイル、
  ソースパス上の jar）がなく、追加も削除もなく、パラメータとソースパスが同じで出力が存在する
  ときは、何もコンパイルしません。そうでなければプログラム全体をコンパイルします。
- `component` でビルドした [HTTP](#http) サーバーは、`target/` から `wasmtime serve` で
  動きます。

## 読める構文

| | |
|---|---|
| 型 | `int`、`long`、`short`、`byte`、`char`、`double`、`float`、`boolean`、`String`、クラス、インターフェース、列挙型、レコード、配列。ローカル変数の `var`。型引数、ワイルドカード（`?`、`? extends T`、`? super T`）、raw 型。型パラメータを持つクラスの内部クラス（`Outer<String>.Inner`） |
| 宣言 | `extends` と `implements` を持つクラス、抽象クラスと抽象メソッド。型パラメータとその境界を持つクラス、インターフェース、レコード、メソッド。フィールド、コンストラクタ、メソッド、本体付きの定数を持つ列挙型。コンパクトコンストラクタと標準コンストラクタを持つレコード。抽象メソッド、`default` メソッド、`static` メソッド、`private` メソッド、定数を持つインターフェース。`sealed`、`permits`、`non-sealed`。static なネストクラス、内部クラス、ローカルクラス、匿名クラス。フィールド、static フィールド、上位クラスのフィールドを隠すフィールド、static 初期化ブロックとインスタンス初期化ブロック。`this(..)`、`super(..)`、`outer.super(..)`、およびその前の文を持つコンストラクタ。メソッドのオーバーロードと可変長引数。`synchronized` メソッドと `volatile` フィールド。`package` と `import` |
| 文 | ローカル変数（`final`、無名変数 `_`）、`if`、`while`、`do`、`for`、配列または `Iterable` に対する拡張 `for`、コロン形式または矢印形式の `case`・パターン・ガードを持つ `switch`、ラベル付きの文、ラベルの有無を問わない `break` と `continue`、`yield`、`return`、`throw`、`catch`（1 つまたは複数の型）と `finally` を持つ `try`、`try`-with-resources、オブジェクトまたはクラスリテラルに対する `synchronized` |
| 式 | プリミティブ型のすべての演算子とその変換、文字列連結、条件演算子、代入と複合代入、キャスト（複数の型への `(A & B) x` も、ラムダとメソッド参照のものも）、型・型パターン・レコードパターンに対する `instanceof`、`switch` 式、コンストラクタの型引数の有無を問わない `new`（`new <T>Box(..)`）、クラス本体の有無を問わない `outer.new Inner()`、`Outer.this`、長さを 1 つ以上指定する配列の生成と初期化子、テキストブロック、型引数の有無を問わないメソッド呼び出し、`super.m(..)`、フィールドを読み書きする `super.x`、`Interface.super.m(..)`、`Outer.super.m(..)`、クラス本体の有無を問わない `new Box<>(..)`、任意の関数型インターフェースに対するラムダとメソッド参照（`Type::name`、`expression::name`、`super::name`、`Type::new`、`int[]::new`、`List<String>::size`、`Type::<T>name`、`Type::<T>new`） |

アノテーションは、宣言に付いたものも型に付いたもの（`List<@Label String>`）も読み飛ばします。
ただし `@Override` と `@FunctionalInterface` はコンパイラと同じように検査し、
`@SuppressWarnings("unchecked")` と `@SafeVarargs` は下記の警告を抑止します。アノテーションの
名前は、そのファイルから見えるアノテーション型のものでなければなりません。`java.lang` と
`java.lang.annotation` のもの、プログラムやライブラリが `@interface` で宣言したものがそれに
あたります。それ以外の名前はコンパイラと同じように拒否します。実行時にアノテーションを読む
ものはありません。`int`、`long`、`short`、`byte` は 2 の補数として桁あふれします。
`float` は演算のたびに単精度へ丸めます。`double` と `float` は、読み戻すとその型の同じ値になる
最短の文字列で表示されます。

```java
sealed interface Shape permits Circle, Rect { }

record Circle(double r) implements Shape { }

record Rect(double w, double h) implements Shape { }

enum Unit {
    CM(1), M(100);

    final int scale;

    Unit(int scale) {
        this.scale = scale;
    }
}

double area(Shape shape) {
    return switch (shape) {
        case Circle c -> Math.PI * c.r() * c.r();
        case Rect(double w, double h) -> w * h;
    };
}

void main() {
    Shape[] shapes = {new Circle(1), new Rect(2, 2), new Rect(2, 3)};
    for (Shape shape : shapes) {
        String kind = shape instanceof Rect r && r.w() == r.h() ? "square" : "shape";
        IO.println(shape + " is a " + kind + " of area " + area(shape));
    }
    for (Unit unit : Unit.values()) {
        String size = switch (unit) {
            case CM -> "small";
            case M -> "large";
        };
        IO.println(unit + " " + unit.ordinal() + " " + unit.scale + " " + size);
    }
}
```

```
Circle[r=1.0] is a shape of area 3.141592653589793
Rect[w=2.0, h=2.0] is a square of area 4.0
Rect[w=2.0, h=3.0] is a shape of area 6.0
CM 0 1 small
M 1 100 large
```

## unchecked の警告

コンパイラが unchecked と警告するもの（raw 型を通したメンバーの呼び出し、raw 型から型引数付きの
型への変換、実行時に検査しきれないキャスト、実行時に検査しきれない型の可変長引数）は、プログラムの
実行前に、コンパイラと同じ形式で標準エラーに表示します。コンパイル（`-o`）ではコンパイル時に
表示し、コンパイルしたプログラムは何も表示しません。`@SuppressWarnings("unchecked")` を付けた
宣言の中では警告しません。`try` が投げる例外のうち受け取れるものを、前の `catch` 節がすべて
受け取っている `catch` 節も、同じ形式で警告します（`unreachable catch clause`）。

```java
void main() {
    List names = new ArrayList();
    names.add("ann");
    IO.println(names);
}
```

```
[ann]
```

`Raw.java` として実行すると、その前に標準エラーへ次を表示します。

```console
Raw.java:3: warning: [unchecked] unchecked call to add(E) as a member of the raw type List
    names.add("ann");
             ^
  where E is a type-variable:
    E extends Object declared in interface List
1 warning
```

## 型パラメータ、ラムダ、メソッド参照

型パラメータは、プログラムを読む間だけ存在します。呼び出しと `new Box<>(..)` の型引数は、
引数、値が求められている型、引数にあるラムダから決まります。型引数がボックスになる箇所では、
値をボクシングまたはアンボクシングします。ラムダとメソッド参照は、抽象メソッドを 1 つ持つ
インターフェースであれば、プログラムのものでもライブラリのものでも作れます。

```java
record Pair<A, B>(A first, B second) {
    <C> Pair<A, C> withSecond(Function<? super B, ? extends C> f) {
        return new Pair<>(first, f.apply(second));
    }
}

<T extends Comparable<T>> T max(List<T> items) {
    T best = items.get(0);
    for (T item : items) {
        if (item.compareTo(best) > 0) {
            best = item;
        }
    }
    return best;
}

<T, R> List<R> map(List<T> items, Function<? super T, ? extends R> f) {
    List<R> out = new ArrayList<>();
    for (T item : items) {
        out.add(f.apply(item));
    }
    return out;
}

void main() {
    List<String> words = new ArrayList<>();
    words.add("pear");
    words.add("fig");
    words.add("banana");
    List<Integer> lengths = map(words, String::length);
    Pair<String, Integer> longest = new Pair<>(max(words), max(lengths));
    IO.println(lengths + " " + longest + " " + longest.withSecond(n -> n * 1.5));
    Comparator<String> byLength = Comparator.comparingInt(String::length);
    Predicate<String> isShort = word -> word.length() < 5;
    IO.println(byLength.reversed().compare("fig", "pear") + " " + map(words, isShort.negate()::test));
}
```

```
[4, 3, 6] Pair[first=pear, second=6] Pair[first=pear, second=9.0]
1 [false, false, true]
```

## 使える JDK のクラス

このサブセットは、JDK のクラスの一部を独自に実装しています。`java.lang`、ストリームと
正規表現と並行処理を含む `java.util`、メモリ上の `java.io`、`java.math`、`java.time`、
`java.net.http`、HTTP サーバーなどです。[使える JDK のクラス](classes.md)に、各クラスの
メンバーと、書式指定、正規表現、ストリーム、ハッシュ表の動作を挙げています。挙げていない
クラスとメンバーは名前を示して拒否します。

## スレッド

`Thread` の開始、`join`、割り込み、名前は言語仕様のとおりに動きます。`synchronized` は
オブジェクトのモニターを保持し、`wait` と `notify` はモニターをスレッドからスレッドへ
受け渡します。`volatile` フィールドを読むと、最後に書き込まれた値が得られます。スレッドが
捕捉しなかった例外は、スレッド名とその例外の原因とともに標準エラーに書き出され、その
スレッドだけが終了します。プログラムは、デーモンでないスレッドがすべて終了したときに終了します。

```java
class Counter {
    private int count;

    synchronized void add() {
        count++;
    }

    synchronized int get() {
        return count;
    }
}

void main() throws InterruptedException {
    Counter counter = new Counter();
    List<Thread> workers = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
        Thread worker = new Thread(() -> {
            for (int k = 0; k < 1000; k++) {
                counter.add();
            }
        }, "worker-" + i);
        workers.add(worker);
        worker.start();
    }
    for (Thread worker : workers) {
        worker.join();
    }
    IO.println(counter.get() + " after " + workers.get(3).getName());
}
```

```
4000 after worker-3
```

インタープリターと JVM では、スレッドはホストの仮想スレッドであり、複数のスレッドが並列に
動きます。`.wasm` にはスタックが 1 本しかないため、スレッドはそのスタックを交代で使います。
スレッドは、待機、スリープ、`yield`、終了のいずれかをするまで実行を続け、待機やスリープを
した場合は、あとで順番が来たときにその位置から再開します。共有するデータをモニター、
`volatile` フィールド、アトミッククラスのいずれかで保護しているプログラムは、どのバックエンド
でも同じ出力になります。それ以外のプログラムで交代の仕方から生じる違いは
[言語との差異](deviations.md#threads)にあります。

このサイトの **Run** とプレイグラウンドは、スレッドを開始するプログラムをページの中で
この `.wasm` にコンパイルして実行します（[言語との差異](deviations.md#threads)）。

`java.util.concurrent` には、ロック、ラッチ、バリア、キュー、並行マップ、エグゼキューター
（プログラムが構成するプール、スケジュール実行のプール、fork/join のプール）、
`CompletableFuture` があり、`java.util` には `Timer` があります。どれも同じモニターの上に
実装されています。`Vector`、`Hashtable`、
`StringBuffer` と、`Collections.synchronizedList` などが返すラッパーは、どのメンバーも
自身のモニターを保持して実行します。

```java
void main() {
    Map<String, Integer> lengths = new ConcurrentHashMap<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
        for (String word : List.of("one", "three", "seven")) {
            pool.execute(() -> lengths.put(word, word.length()));
        }
    }
    CompletableFuture<Integer> total = CompletableFuture.supplyAsync(() -> lengths.get("three"))
        .thenApply(n -> n + lengths.size());
    IO.println(lengths + " " + total.join());
}
```

```
{one=3, seven=5, three=5} 8
```

## HTTP

`java.net.http` には HTTP クライアントがあります。`HttpClient` は `HttpRequest` を送り、
`HttpResponse` を返します。レスポンスのボディは、ハンドラーが応答から作った値です。`send` は
応答をすべて受け取ってから戻り、`sendAsync` は `CompletableFuture` を返します。すべての
リクエストは [`rontolisp:fetch`](../guides/http-fetch.md) を通るため、fetch が使えるすべての
トランスポートで同じソースが動きます。

| ターゲット | ビルド | トランスポート |
|---|---|---|
| インタプリタ、JVM | `rontolisp App.java`、`-o App.class` | JDK の `HttpClient` |
| WASM コンポーネント | `-o app.wasm --component`、`wasmtime run -S http=y` で実行 | `wasi:http` |
| ネイティブ実行ファイル | `--native` | 実行ファイル自身のクライアント |

素の Preview 1 モジュールにはトランスポートがありません。コンパイルはそのプログラムを拒否し、
トランスポートを与えるフラグを示します。`java.net` は `java.base` に含まれます。コンパクト
ソースファイルでは、`import module java.net.http;` でクライアントをインポートします。

```java
import module java.net.http;

void main() throws IOException, InterruptedException {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request = HttpRequest.newBuilder(URI.create("https://httpbin.ik.am/get"))
        .header("Accept", "application/json")
        .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    IO.println(response.statusCode() + " " + response.headers().firstValue("content-type").orElse("none"));
    HttpRequest missing = HttpRequest.newBuilder(URI.create("https://httpbin.ik.am/status/404")).build();
    IO.println(client.sendAsync(missing, HttpResponse.BodyHandlers.discarding())
        .thenApply(HttpResponse::statusCode)
        .join());
}
```

```
200 application/json
404
```

ビルダーは、JDK のビルダーが拒否するものを同じメッセージで拒否します。制限されたヘッダー、
不正なヘッダー、絶対の `http` または `https` でない URI です。
`followRedirects(Redirect.NORMAL)` と `ALWAYS` は、JDK のクライアントと同じ規則で 301、302、
303、307、308 をたどり、途中の応答は `previousResponse()` で取り出せます。接続を拒否された
場合は `ConnectException` になります。`HttpRequest.Builder.timeout` は応答が届き始めるまでの
時間を、`HttpClient.Builder.connectTimeout` は接続にかけてよい時間を制限します。どちらも
`java.time.Duration` を取り、制限を過ぎたリクエストは `HttpTimeoutException` または
`HttpConnectTimeoutException` になります。トランスポートが提供できないものと、用意していない
メンバーは[言語との差異](deviations.md#the-http-client)にあります。

`com.sun.net.httpserver` には HTTP サーバーがあります。`HttpServer` にコンテキストを登録し、
`HttpHandler` が `HttpExchange` に応答します。サーバーはターゲットのトランスポートを通して
リクエストを受けるため、同じソースがどのターゲットでもサーバーになります。

| ターゲット | ビルド | ポートを開くもの |
|---|---|---|
| インタプリタ、JVM | `rontolisp App.java`、`-o App.class` | プログラム（`create` に渡したアドレス） |
| WASM コンポーネント | `-o app.wasm --component`、`wasmtime serve -S cli=y app.wasm` で実行 | ホスト（`--addr`）。リクエストごとに `wasi:http` でプログラムを呼びます |
| ネイティブ実行ファイル | `--native` | 実行ファイル（`create` に渡したアドレス） |

素の Preview 1 モジュールはポートを開けません。`create` は、`--component` と `--native` を
示す `IOException` を投げます。コンパクトソースファイルでは、`import module jdk.httpserver;` で
サーバーをインポートします。

プログラムは、開始したサーバーが動いているあいだ終了しません。`main` が戻っても待機しても、
`stop` が呼ばれるまでリクエストに応答します。ハンドラーは、サーバーに設定した
エグゼキューターのスレッドで実行され、プログラムのほかのスレッドを待つことができます。
コンテキストには、フィルター（`getFilters`）とオーセンティケーターを設定できます。
`wasmtime serve` では、ホストが必要に応じてプログラムを起動し、`create` に渡したアドレスは
使われません。
`sendResponseHeaders(status, length)` の `length` は、ボディの長さです。`-1` はボディが
ないことを、`0` は長さを事前に知らせないボディを表します。長さを知らせないボディは、
トランスポートが対応していれば（`wasmtime serve`）、書き込むたびにチャンクとして送られます。
それ以外のトランスポートでは、全体がまとめて送られます。

次のプログラムは、コンテキストを 1 つ持つサーバーを開始し、`main` からそのサーバーに
リクエストを 1 回送ります。サーバーとクライアントが同じプロセスで動くため、実行できるのは
スレッドのあるターゲット（インタプリタと JVM）です。

```java
import module java.net.http;
import module jdk.httpserver;

void main() throws IOException, InterruptedException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/hello", exchange -> {
        String name = exchange.getRequestURI().getQuery();
        byte[] body = ("Hello, " + name + "!").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    });
    server.start();

    URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hello?Ann");
    HttpResponse<String> response = HttpClient.newHttpClient()
        .send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
    IO.println(response.statusCode() + " " + response.body());
    server.stop(0);
}
```

```
200 Hello, Ann!
```

サーバーだけのプログラムと、各ターゲットでの実行手順は
[`examples/java/http`](https://github.com/making/rontolisp/tree/develop/examples/java/http)
にあります。トランスポートが提供できないものと、用意していないメンバーは
[言語との差異](deviations.md#the-http-server)にあります。

言語と動作が異なる点と、拒否される構文は[言語との差異](deviations.md)にあります。
