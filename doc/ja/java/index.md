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

`--no-gc` はスカラー値のリアクタをコンパイルします（[`--no-gc` のリアクタ](#a-reactor-on---no-gc)）。
プログラムを与えずに `--source-language java` を指定すると REPL が起動します
（[プロンプトで](#at-the-prompt)）。

[`examples/java/`](https://github.com/making/rontolisp/tree/develop/examples/java) に、
複数のパッケージからなるプログラムとその期待される出力、HTTP サーバーとその
クライアント、小さな httpbin、Servlet のアプリケーション、PostgreSQL に JDBC でつなぐ
プログラム、およびソース jar から読んだライブラリでフォームを検証するプログラムがあります。
ライブラリを使うものと、種類ごとの最小のプログラム（`hello-java` はコンパクトソースファイル、
`hello-servlet`、`hello-mvcfn`）は [Maven プロジェクト](#a-maven-project)です。

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
引数のないコンストラクタで作ったインスタンスに対して呼ばれます。引数の配列はプログラムの
コマンドライン引数で、プログラム名は含みません。インタープリタでは `--` の後ろの引数
（`rontolisp Main.java -- a b`）、コンパイルしたプログラムではホストが渡す引数
（`java Main a b`、`wasmtime run main.wasm a b`、`--native` の実行ファイルの引数）です。`main` の
ないプログラムはリアクタで、その入口はホストが呼びます。ソースツリーに `@WitExport` が付いた
クラスがあれば、それらのクラスの WIT world をエクスポートするコンポーネントです
（[WIT world をエクスポートする](wit.md#exporting-a-wit-world)）。なければ、`--no-wasi` か `--no-gc` で
コンパイルしたモジュールが、プログラムの開始位置のクラスの public static メソッドをエクスポート
します（[public static メソッドのリアクタ](#a-reactor-of-public-static-methods)）。
トップレベルにメソッドやフィールドを書いたファイルはコンパクトソースファイルです。それらは
1 つのクラスのメンバーになり、[使える JDK のクラス](classes.md)はすべて import なしで使えます。同じファイルに
書いたクラスも、そのクラスのメンバーになります。`static` を付けたクラス、レコード、列挙型、
インターフェース以外は内部クラスです。

## public static メソッドのリアクタ

`main` も `@WitExport` のクラスもないプログラムは、`--no-wasi` で、何もインポートしない
モジュールにコンパイルされます。モジュールは、プログラムの開始位置のクラスの public static
メソッドを、それぞれメソッドの名前でエクスポートします。Common Lisp のプログラムの
`rontolisp:wasm-export` と同じエクスポートです
（[リアクターモジュールを手書きで呼ぶ](../guides/wasm-browser.md#reactor-modules-by-hand)）。
アノテーションも依存も要りません。JDK では普通のクラスで、そのメソッドは[テスト](testing.md)
から呼べます。

```console
$ cat Calc.java
public class Calc {

    static int calls;

    public static int add(int a, int b) {
        return a + b;
    }

    public static String greet(String name) {
        calls++;
        return "Hello, " + name + "! (" + calls + ")";
    }
}
$ rontolisp Calc.java -o calc.wasm --no-wasi --emit-js-glue
```

`--emit-js-glue` は、モジュールの隣に `calc.js` を書きます。`instantiate` はインスタンスを作って
`_initialize` を呼び、static の初期化を 1 度だけ実行します。各エクスポートは JavaScript の値を
受け渡す関数になります。同じコードが Node 22 以降、ブラウザ、Cloudflare Workers で動きます。

```js
import { readFile } from "node:fs/promises";
import { instantiate } from "./calc.js";

const calc = instantiate(new WebAssembly.Module(await readFile("calc.wasm")));
console.log(calc.add(2, 3));       // 5
console.log(calc.greet("Java"));   // Hello, Java! (1)
```

- 引数の型は `byte`、`short`、`int`、`long`、`double`、`boolean`、`String` のいずれかです。
  戻り値の型も同じで、`void` も使えます。`long` は JavaScript では `BigInt` です。`String` は
  線形メモリ上の UTF-8 として渡り、glue がコピーします。それ以外の型はメソッドの位置で拒否
  されます。同じ名前の public static メソッドが 2 つあっても拒否されます。モジュールは 1 つの
  名前を 1 度しかエクスポートできないためです。
- エクスポートが捕捉しなかった例外はトラップになります。`String` の戻り値が `null` のときも
  トラップです。処理を続けるホストは、トラップの後でインスタンスを作り直します。
- モジュールは wasm-GC です。スカラー値のクラスは `--no-gc` で、どのエンジンでも動くモジュールに
  なります（[`--no-gc` のリアクタ](#a-reactor-on---no-gc)）。
- インタプリタ、JVM、WASI のモジュール、`--native` では、実行する `main` がないので拒否されます。
  コンポーネントがエクスポートするのは WIT world で、それは `@WitExport` で宣言します。

[`examples/java/no-wasi/`](https://github.com/making/rontolisp/tree/develop/examples/java/no-wasi)
は最小の例で、そのエクスポートを `wasmtime run --invoke` で呼びます。
[`examples/cloudflare-workers/hello-java/`](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/hello-java)
は、このようなクラスを使う Worker です。

### `--no-gc` のリアクタ

`--no-gc` を付けると、同じクラスが素のモジュールにコンパイルされます。保持する値がすべて
プリミティブ、`String`、`double[]` であれば、wasm-GC の有無を問わずどの WebAssembly エンジンでも
動きます（[`--no-gc` のバックエンド](../guides/wasm-nogc.md)）。
[`examples/cloudflare-workers/hello-java/`](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/hello-java)
の `Worker` は、`--no-wasi` で 3,956 バイト、`--no-gc` で 1,103 バイトです。

```console
$ rontolisp Calc.java -o calc.wasm --no-gc
```

glue はありません。ホストはエクスポートを直接呼びます。`String` の引数は、モジュールの
`__ronto_alloc` エクスポートで確保したメモリに書き込み、ポインタと長さで渡します。`String` の
戻り値も、同じ組で返ります。

```js
const calc = new WebAssembly.Instance(new WebAssembly.Module(await readFile("calc.wasm"))).exports;
const text = (s) => {
  const bytes = new TextEncoder().encode(s);
  const at = calc.__ronto_alloc(bytes.length);
  new Uint8Array(calc.memory.buffer, at, bytes.length).set(bytes);
  return [at, bytes.length];
};
const [at, length] = calc.greet(...text("Java"));
console.log(new TextDecoder().decode(new Uint8Array(calc.memory.buffer, at, length)));   // Hello, Java! (1)
```

- static の初期化は、モジュールのインスタンス化のときに 1 度だけ実行されます。`int` と `long` は
  言語の定めどおりに桁あふれします。エクスポートが捕捉しなかった例外はトラップになります。
  `double[]` の範囲外の添字と、`null` の `String` の戻り値もトラップです。
- このバックエンドにないものは、ソース上の位置で名前を挙げて拒否されます。対象は、オブジェクトの
  読み出し、ほかの型の配列、ラムダ、ボックス、`StringBuilder`、`try`、初期化が例外を投げうるクラス、
  呼び出しの中で代入される static の `String`、このバックエンドがコンパイルできないライブラリの
  メンバーです。最後のものには、`toUpperCase` と `+` の `float` があります。
  `main` のあるプログラムはコマンドで、`--no-gc` はコマンドを作りません。`--component` も拒否されます。

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

`import` には、クラス、オンデマンドのパッケージ、`module java.base`（`module java.sql`、
`module java.naming`、`module java.logging` のものを除く使える JDK のクラスすべて）、`module java.sql`（`java.sql`、`javax.sql`、
`java.util.logging`、`javax.transaction.xa`、`javax.xml.transform`）、`module java.naming`（`javax.naming`、
`javax.naming.spi`）、`module java.logging`（`java.util.logging`）を指定できます。オンデマンドのパッケージは
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
  `--java-repository` でリポジトリを、`--java-snapshots` で Central の snapshot リポジトリを
  追加でき、`--java-update-snapshots`（`mvn -U` 相当）で SNAPSHOT を問い合わせ直せます。指定したライブラリがソース jar を公開して
  いなければ拒否します。依存先がソース jar を公開していない場合は警告にとどめ、そのクラスは
  無いものとして扱います。

[`examples/java/yavi`](https://github.com/making/rontolisp/tree/develop/examples/java/yavi)
には、YAVI（`--java-source-dep am.ik.yavi:yavi:0.18.1`）を使うプログラムが 2 つあり、どの
バックエンドでも動きます。1 つはレコードを検証し、もう 1 つはスキーマでマップからレコードを
読み取ります。
[`examples/java/mustache`](https://github.com/making/rontolisp/tree/develop/examples/java/mustache)
は、jmustache（`--java-source-dep com.samskivert:jmustache:1.16`）で、リソースにある
レイアウト、ページ、パーシャルからページを描きます。jmustache は、テンプレートが指す名前を
プログラムのクラスの値（レコードの構成要素、ゲッター）からリフレクションで名前を使って見つけます。
このサブセットは、プログラム自身のクラスについてこれに答えます（[言語との差異](deviations.md#what-differs)）。

これは `--java-classpath` や `--java-dep` とは別のものです。そちらは Common Lisp や Clojure の
`java:` 呼び出しのためにホストが実行するクラスファイルで、Java サブセットのプログラムは
それを呼びません。

ライブラリが動くかどうかは、プログラムがそのどこに到達するかで決まります。コンパイルするのは、
呼び出したメソッド、インスタンスを作ったクラス、使った static 初期化だけで、それぞれに
プログラム自身のコードと同じ検査をします。したがって、プログラムが実行する部分がサブセットと
[使える JDK のクラス](classes.md)の範囲に収まっていれば、そのライブラリは動きます。
プログラムが到達した文がサブセットに無いもの（サブセットに無い JDK のクラスやメソッド、
ソースパスのどのルートにも無いクラス）を参照していれば、ライブラリ内のその位置の警告になり、
プログラムからそこへ至る経路を添えます。その文は実行された時点で `NoClassDefFoundError`
（クラス）または `NoSuchMethodError`（メソッド）を投げます。JVM も、コードが参照する先との
リンクを、そのコードを初めて実行する時点で行います。

```
warning: geo-sources.jar!/geo/Table.java:42:54: the class java.util.Currency is not supported by the experimental Java subset yet; where it runs, it throws java.lang.NoClassDefFoundError
  reached through geo.Table.column(String,geo.Table.ToMoney)
```

そのようなクラスの値は存在しません。そのクラスへの型検査は `false`、キャストは `null` だけを
通し、そのクラスの `catch` 節は何も捕まえません。プログラム自身のファイルでは、サブセットに
無いものは書かれた位置のエラーです。

ライブラリが宣言の中でだけ名前を挙げるクラス（プログラムが呼ばないメソッドの引数の型など）は
素通りします。そのようなクラスを型に含むメンバーをリフレクションで扱う（一覧する、名前で
探す）と、その場で `NoClassDefFoundError` を投げます。JDK のリフレクションも、クラスパスに
無いクラスに対して同じものを投げます。ライブラリ内のアノテーションは、その型がソースパスに無ければ読み飛ばします。
コンパイラがクラスファイルをその型なしで読むのと同じで、アノテーションだけの依存は無くても
かまいません。ライブラリが別のクラスの名前のファイルに宣言したクラスは、そのパッケージの
どのファイルにあっても、クラスファイルがある場合と同じように見つかります。ライブラリは全体を
まとめてコンパイルされているからです。プログラム自身のソースツリーでは、`java` ランチャーと
同じく、そのファイルを読んだ後で見つかります。同じ名前のファイルがプログラムのソースツリーと
ソースパスの両方にあれば、プログラムのものを使います。

### リソース

クラスやクラスローダーの `getResourceAsStream` は、プログラムのソースツリーか、ソースパス上の
ライブラリのファイルを読みます。`getResource` はその URL を返します。ファイルはコンパイル時に見つけ、その内容を出力に含めます。
そのため `.wasm` はファイルシステムなしで読めます。`wasmtime serve` が動かすコンポーネントには
ファイルシステムがまったくないので、サーバーのプログラムが読むファイル（テンプレートなど）は
リソースにします。定数の名前は、その名前のファイル一つを含めます。実行時に組み立てた名前は、ローダーが見つけうるファイルをすべて含めます。ソースパスの
ファイルと、プログラムのローダーに問い合わせうる場合はソースツリーのファイルです。先頭か末尾が定数の
文字列連結で組み立てた名前（テンプレートを読むローダーの `"templates/" + name + ".mustache"` など）は、
そうした名前が見つけうるファイルだけを含めます。ソースツリーと jar では、パスが定数の先頭で始まり
定数の末尾で終わるファイルです。ソースパスのディレクトリは組み立てた部分の `..` を解決するので、
名前が定数の末尾で終わるファイルです。ソースファイル
（`.java`）、クラスファイル（`.class`）、名前がドットで始まるファイルとディレクトリは含めません。
含めるファイルが 10,000 個か 16 MiB を超えるとき、または調べる項目が 100,000 個を超えるときは、
コンパイル時に拒否します。ホームディレクトリのような大きなディレクトリに置いたプログラムでは、
そうしたリソースをソースパス上の専用のディレクトリに置きます。

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
プログラムのローダーの親はシステムローダーで、その親はプラットフォームローダー
（`ClassLoader.getPlatformClassLoader()`）です。プラットフォームローダーはどちらも見つけません。
プレイグラウンドでは、アップロードしたファイルがプログラムのソースツリーです。

リソースの URL は、コンパイルした場所のファイルを指します。ソースツリーとソースパスのディレクトリでは
`file:` と絶対パス、jar では `jar:file:` と jar のパス、`!/` とエントリ名で、ランチャーと同じ形に
書きます。`openStream` は出力に含めた内容を読むため、そのファイルが無いマシンでも読めます。
`getResources` は、その名前を持つ場所すべての URL を、ソースツリーを先にして返します。
ランチャーと同じくディレクトリもリソースです。ソースツリーではその URL が `/` で終わり、ストリームは
項目の名前を 1 行に 1 つずつ読みます。jar では、jar がそのディレクトリの項目を持つときだけ見つけます。

```
URL url = Main.class.getResource("data.txt");   // file:/home/me/app/data.txt
String text = new String(url.openStream().readAllBytes());
```

`ResourceBundle.getBundle` は JDK と同じ手順でバンドルを探します。候補のロケールを具体的なものから
順にたどり、それぞれで `ResourceBundle` を継承したバンドル名の public クラスを、無ければ
`.properties` ファイルを探します。ファイルは UTF-8 として読み、UTF-8 でなければ ISO 8859-1 として
読みます。基底のバンドルしか見つからないか、何も見つからないときは、デフォルトロケールの候補を
探します。定数の基底名は、その `.properties` ファイルと、同じパッケージにあって基底名を名前に
持つクラスを含めます。実行時に組み立てた基底名は、基底名の定数の先頭の下にある `.properties`
ファイル（`"app.texts." + name` なら `app/texts/` の下）を含め、ソースツリーとソースパスのバンドルクラスをすべて探します。`ResourceBundle` を継承するクラスと、
そのようなクラスを継承するクラスで、ファイルが宣言している名前で探します。メンバークラスは、JDK と同じく
`$` を含む名前（`app.Texts$Words`）のバンドルになります。基底名だけ、または基底名と
ロケールを渡す `getBundle` は、呼び出したクラスのローダーに問い合わせます。ライブラリの
`ResourceBundle.Control`（`Control.getControl`、`getNoFallbackControl`）やプログラムの
`Control` は、形式、候補とフォールバックのロケール、バンドルの作り方、保持するかどうかを決め、
JDK と同じ順に呼ばれます。`Control` を渡す `getBundle` は、どの名前でも指しうるため、
すべてのファイルとバンドルクラスを含めます。モジュールを渡す
`getBundle` は、そのモジュールのローダーのバンドルを探します。クラスのモジュールはローダーの
名前のないモジュールで、JDK のクラスでは `java.base` です。`java.base` はバンドルを持ちません。
`Properties.storeToXML` と `loadFromXML` は、properties DTD の XML 文書を書き、読みます。

```
ResourceBundle messages = ResourceBundle.getBundle("app.messages", Locale.UK);
// app/messages_en_GB.properties、app/messages_en.properties、app/messages.properties の順
```

`ServiceLoader.load` は、ローダーのリソースにある
`META-INF/services/<サービス>` ファイルが名前を挙げるプロバイダーをコンパイル時に見つけます。
ソースツリーのファイルを先に、続いてソースパスのファイルを順に読み、各プロバイダーはプログラムが
求めた時点で生成します。`Class<?>` やジェネリックメソッドの `Class<T>` は、それらのファイルが
名前を挙げるサービスの中から、実行時にクラス名で選びます。プロバイダーは JDK と同じ場合に失敗します。クラスが見つからない、型が
違う、引数のない public コンストラクタが無い、生成できない、のいずれかです。

### 到達可能性メタデータによるリフレクション

コンパイルしたプログラムには、実行時に名前でクラスを探すためのクラスパスがありません。プログラムが
実行時に計算した名前で探すクラスと、ライブラリのクラスのうちリフレクションで使うものは、GraalVM の
到達可能性メタデータに宣言します。GraalVM の `native-image` が読むのと同じファイルなので、一つの
メタデータファイルを両方のコンパイラが読みます。ファイルはコンパイル時に、ソースツリーとソースパスの
各ルートの `META-INF/native-image/` の下から深さを問わず読みます（慣例では
`META-INF/native-image/<groupId>/<artifactId>/`）。ライブラリの jar や sources jar は自身の
メタデータを含み、Maven プロジェクトではプログラムのメタデータを `src/main/resources` に置きます。
`reachability-metadata.json` からは `reflection` セクションの項目を、旧形式の `reflect-config.json`
からはその項目を読みます。ほかのセクションは読みません（リソース、バンドル、サービスは、メタデータの
内容にかかわらずコンパイル時に見つけます）。

```json
{
  "reflection": [
    {
      "condition": { "typeReached": "com.example.Registry" },
      "type": "com.example.reflection.English",
      "methods": [
        { "name": "<init>", "parameterTypes": [ "java.lang.String" ] },
        { "name": "greet", "parameterTypes": [] }
      ],
      "fields": [ { "name": "label" } ]
    }
  ]
}
```

- **項目が名前を挙げるクラスは、プログラムが計算した名前に対する `Class.forName` で見つかります。**
  プログラムのクラス、ソースパスにあるライブラリのクラス、JDK のクラスのいずれでもかまいません。
  どの項目も宣言しない名前は見つからず（`ClassNotFoundException`）、GraalVM の `native-image` も
  既定ではそう答えます。JDK はクラスパスにあるどのクラスも見つけます。アノテーション付きクラスの索引が
  挙げるクラスは、これまでどおり見つかります（[アノテーション](servlet.md#annotations)）。定数の名前を
  渡す `Class.forName` は、項目がなくてもそのクラスを見つけます。`native-image` がこの呼び出しを
  コンパイル時に畳み込むのと同じです。
- **項目が名前を挙げるライブラリのクラスは、JDK と同じようにリフレクションに答えます。** メンバーを
  一覧にし、名前と型で検索し、メンバーの型と修飾子、クラスの修飾子を返します。実行するのは項目が
  宣言するメンバーです。メソッドとコンストラクタ（`<init>`）は名前と引数の型で、フィールドは名前で
  宣言します。`allDeclaredMethods`、`allPublicMethods`、`allDeclaredFields`、`allPublicFields`、
  `allDeclaredConstructors`、`allPublicConstructors` は、その種類のメンバーをすべて宣言します。
  旧形式で `parameterTypes` のない項目は、その名前のメソッドをすべて宣言します。それ以外のメンバーは、
  使った箇所で実行時に拒否されます（`UnsupportedOperationException`、リフレクションからしか使われない
  メンバーとして）。`native-image` も拒否するメンバーです。引数のないコンストラクタは、宣言の有無に
  かかわらず実行します。定数でメンバーを名指す検索（`Plugin.class.getMethod("greet")`）は、その
  メンバーを宣言します。`native-image` がこうした検索を畳み込むのと同じです。どの項目も宣言しない
  ライブラリのクラスは、これまでどおりメンバーを返しません（[相違点](deviations.md#what-differs)）。
  プログラムのクラスには項目が要りません。すべてのメンバーを返し、すべてを実行します。
- **項目が名指す JDK のメンバーは、プログラムに含まれます。** 定数でメンバーを名指す検索が含めるのと
  同じです。`{"type": "java.lang.String", "methods": [{"name": "isBlank", "parameterTypes": []}]}`
  があれば、テンプレートエンジンが `String` に対する `{{blank}}` を実行できます。
- **`typeReached` の条件は、プログラムがそのクラスに到達すると満たされます。** 到達するのは、
  プログラムのクラスすべてと、プログラムが static 初期化かインスタンスを必要とするライブラリや JDK の
  クラス、およびそれらの上位のクラスすべてです。条件のない項目は常に有効です。`native-image` は条件を実行時に
  確かめるのに対し、rontolisp はコンパイル時に確かめます。そのため、プログラムがどこかでそのクラスに
  到達するなら、項目は実行の最初から有効です。
- 項目が名前を挙げるクラスに対してリフレクションを使わないプログラムは、メタデータの有無にかかわらず
  同じバイト列にコンパイルされます。

`--java-metadata-repository` を指定すると、GraalVM の到達可能性メタデータの共有リポジトリ
（`oracle/graalvm-reachability-metadata`）も、`--java-source-dep` が解決したライブラリについて
読みます。値は `org.graalvm.buildtools:graalvm-reachability-metadata` のバージョン（その
`repository` zip を sources jar と同じ方法で取得します）か、リポジトリのディレクトリか zip です。
ライブラリのメタデータは、その `index.json` がライブラリのバージョンに対して挙げるものを使い、
挙げていなければ最新のものを使います。JDK のクラスを挙げる項目は読みません。これらの項目は、
メタデータを作った環境で JDK 自身のクラスがリフレクションで求めたものの記録であり、このサブセットの
ライブラリはそれを求めないからです。Maven プラグインの `metadataRepository` も同じです。

```console
$ rontolisp app/Main.java --java-source-dep org.example:geo:1.2.0 --java-metadata-repository 1.1.19
```

[`examples/java/reflection`](https://github.com/making/rontolisp/tree/develop/examples/java/reflection)
は、リソースが挙げる名前でクラスを探してリフレクションで呼び出し、YAVI の `ViolationDetail` の
プロパティを名前で取得します。JDK、`native-image`、すべてのバックエンドで同じ行を出力します。

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
ベクタを渡します。`int`、`long`、`short`、`byte`、`char` の引数に渡す整数はその型の値でなければ
なりません。コンパイルしたプログラムは型の宣言どおりに扱います（wasm では型の範囲を超える整数で
トラップし、JVM では整数でない値でエラーになります）。配列型の引数に渡す配列も、その型が作るベクタで、
要素型の値を持つものでなければなりません。`int[]` は `(make-array n :element-type '(unsigned-byte 32))`、
`byte[]` は `'(unsigned-byte 8)`、`char[]` と `short[]` は `'(unsigned-byte 16)`、`double[]` は
`'double-float`、`float[]` は `'single-float`、`boolean[]` は `'bit`、その他は要素型のないベクタです
（wasm ではそれ以外のベクタを読む箇所でトラップします）。`int`、`long`、`short`、`byte` を返す
`native` メソッドが返す整数は、
その型の値と同じく型の幅の下位ビットに切り詰めます。

プログラムがインポートする WIT インタフェースは、`@WitImport` を付けた Java のインタフェースとして
書き、エクスポートする WIT world は `@WitExport` を付けたクラスとして書きます。同じソースが JDK
でもどのターゲットでも動きます（[WIT のインタフェースと world](wit.md)）。

## ツール

`rontolisp test FILE.java` は、ファイルのクラスが JUnit Jupiter のアノテーションで宣言した
テストを、プログラムの依存からコンパイルした JUnit Jupiter と、サブセットが自前で持つ AssertJ の
一部に対して実行し、その判定を終了ステータスにします（[Java のプログラムをテストする](testing.md)）。
`rontolisp format` は `.java` ファイルを拒否します。このコマンドが整形するのは S 式です。

## Maven プロジェクト

[Maven プラグイン](../compiling/maven-plugin.md#a-java-project)の `wasm` ゴールは、
Maven プロジェクトの `src/main/java` にあるプログラムを `package` で WebAssembly に
コンパイルします。同じソースは引き続き javac がコンパイルし、そのテストは surefire が
実行します。`test` ゴールはそのテストを rontolisp が作るプログラムで実行します
（[Java のプログラムをテストする](testing.md#a-maven-project)）。
[`examples/java/`](https://github.com/making/rontolisp/tree/develop/examples/java) のうち
ライブラリを使うアプリケーションは、こうしたプロジェクトです。

## 読める構文

| | |
|---|---|
| 型 | `int`、`long`、`short`、`byte`、`char`、`double`、`float`、`boolean`、`String`、クラス、インターフェース、列挙型、レコード、配列。ローカル変数の `var`。型引数、ワイルドカード（`?`、`? extends T`、`? super T`）、raw 型。型パラメータを持つクラスの内部クラス（`Outer<String>.Inner`） |
| 宣言 | `extends` と `implements` を持つクラス、抽象クラスと抽象メソッド。型パラメータとその境界を持つクラス、インターフェース、レコード、メソッド。フィールド、コンストラクタ、メソッド、本体付きの定数を持つ列挙型。コンパクトコンストラクタと標準コンストラクタを持つレコード。抽象メソッド、`default` メソッド、`static` メソッド、`private` メソッド、定数を持つインターフェース。`sealed`、`permits`、`non-sealed`。static なネストクラス、内部クラス、ローカルクラス、匿名クラス。フィールド、static フィールド、上位クラスのフィールドを隠すフィールド、static 初期化ブロックとインスタンス初期化ブロック。`this(..)`、`super(..)`、`outer.super(..)`、およびその前の文を持つコンストラクタ。メソッドのオーバーロードと可変長引数。レシーバーパラメータ（`C this`、内部クラスのコンストラクタでは `Outer Outer.this`。アノテーションを書くためのもので、パラメータにはなりません）。`synchronized` メソッドと `volatile` フィールド。`package` と `import` |
| 文 | ローカル変数（`final`、無名変数 `_`）、`if`、`while`、`do`、`for`、配列または `Iterable` に対する拡張 `for`、コロン形式または矢印形式の `case`・パターン・ガードを持つ `switch`、ラベル付きの文、ラベルの有無を問わない `break` と `continue`、`yield`、`return`、`throw`、`catch`（1 つまたは複数の型）と `finally` を持つ `try`、`try`-with-resources、オブジェクトまたはクラスリテラルに対する `synchronized`、`assert`（有効にはなりません。[言語との差異](deviations.md)） |
| 式 | プリミティブ型のすべての演算子とその変換、文字列連結、条件演算子、代入と複合代入、キャスト（複数の型への `(A & B) x` も、ラムダとメソッド参照のものも）、型・型パターン・レコードパターンに対する `instanceof`、`switch` 式、コンストラクタの型引数の有無を問わない `new`（`new <T>Box(..)`）、クラス本体の有無を問わない `outer.new Inner()`、`Outer.this`、長さを 1 つ以上指定する配列の生成と初期化子、テキストブロック、型引数の有無を問わないメソッド呼び出し、`super.m(..)`、フィールドを読み書きする `super.x`、`Interface.super.m(..)`、`Outer.super.m(..)`、クラス本体の有無を問わない `new Box<>(..)`、任意の関数型インターフェースに対するラムダとメソッド参照（`Type::name`、`expression::name`、`super::name`、`Type::new`、`int[]::new`、`List<String>::size`、`Type::<T>name`、`Type::<T>new`） |

アノテーションは、宣言に付いたものも型に付いたもの（`List<@Label String>`）も読み飛ばします。
ただし `@Override` と `@FunctionalInterface` はコンパイラと同じように検査し、
`@SuppressWarnings("unchecked")` と `@SafeVarargs` は下記の警告を抑止します。アノテーションの
名前は、そのファイルから見えるアノテーション型のものでなければなりません。`java.lang` と
`java.lang.annotation` のもの、プログラムやライブラリが `@interface` で宣言したものがそれに
あたります。それ以外の名前はコンパイラと同じように拒否します。実行時には、クラス、メンバー、
引数、レコードのコンポーネント、パッケージのアノテーションをリフレクションで読めます。宣言に
書かれたジェネリックな型も JDK と同じように返します（[言語との差異](deviations.md#what-differs)）。
これらを読まないプログラムは、そのための仕組みを何も持ちません。`int`、`long`、`short`、`byte` は 2 の補数として桁あふれします。
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
インターフェースであれば、プログラムのものでもライブラリのものでも作れます。型引数付きの型を
受けるメソッドに raw 型を渡す呼び出しは、コンパイラと同じ型になります。値が求められている型は
型引数を決めず、値の型はメソッドの戻り値の型のイレイジャーです。たとえば
`Collections.max(rawList)` は `Comparable` です。

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
正規表現と並行処理を含む `java.util`、メモリ上と[ファイル](#files)の `java.io`、
`java.nio.file`、`java.math`、`java.time`、`java.net.http`、HTTP サーバー、[ソケット](#sockets)、[ダイジェストと鍵](#digests-and-keys)などです。[使える JDK のクラス](classes.md)に、各クラスの
メンバーと、書式指定、正規表現、ストリーム、ハッシュ表の動作を挙げています。挙げていない
クラスとメンバーは名前を示して拒否します。

## ファイル

`File`、ファイルのストリーム、`RandomAccessFile`、`Path`、`Files` はホストのファイルシステムを
使います。インタープリターと JVM では、プロセスが使えるすべてのファイルを使い、相対パスは
作業ディレクトリを基準に解決します。`.wasm` では、ホストが事前に開いた（プリオープンした）
ディレクトリを使い、相対パスは最初のディレクトリを基準に解決します。拒否は JDK と同じ例外と
文言です。ブラウザーでは、ファイルに触れるプログラムを実行前に拒否します。ページには
ファイルシステムがないためです。

```bash
cat > Notes.java <<'END'
import java.nio.file.*;

void main() throws Exception {
    Path notes = Path.of("notes.txt");
    Files.writeString(notes, "one\ntwo\n");
    IO.println(Files.readAllLines(notes) + " " + Files.size(notes));
}
END
rontolisp Notes.java                                         # [one, two] 8
rontolisp Notes.java -o notes.wasm && wasmtime run --dir . notes.wasm
```

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
| WASM コンポーネント | `-o app.wasm --component`、`wasmtime run` で実行 | `wasi:http` |
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
| WASM コンポーネント | `-o app.wasm --component`、`wasmtime serve app.wasm` で実行 | ホスト（`--addr`）。リクエストごとに `wasi:http` でプログラムを呼びます |
| ネイティブ実行ファイル | `--native` | 実行ファイル（`create` に渡したアドレス） |
| WASM リアクター | `-o worker.wasm --no-wasi` | ホスト。リクエストごとにモジュールの `handle-request` エクスポートを呼びます（[Cloudflare Worker](#a-cloudflare-worker)） |

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
[言語との差異](deviations.md#the-http-server)にあります。Jakarta Servlet API に対して書いた
プログラムも、一緒にコンパイルしたコンテナを通してこのサーバーで動きます
（[Servlet アプリケーション](servlet.md)）。Spring MVC の WebMvc.fn の書き方でルーティングを関数として
書いたプログラムも、同じコンテナの上で動きます（[WebMvc.fn アプリケーション](webmvc-fn.md)）。

### Cloudflare Worker

サーバーのプログラムを `--no-wasi` でコンパイルすると、リアクターになります。リアクターは、
何もインポートせず、ホストから呼び出されるモジュールです。プログラムは書き換えません。
`main` はインスタンスの開始時に 1 回だけ実行されます。サーバーを作る箇所では、ポートを
バインドする代わりにアプリケーションをリアクターに登録します。リクエスト 1 つが
`handle-request` エクスポートの呼び出し 1 回に対応します。`--emit-worker-entry` を付けると、
このモジュールを Cloudflare Worker にする JavaScript がモジュールの隣に出力されます。
`--emit-js-glue` が書くグルーと、その上の Worker の `index.js` です。`index.js` は一度だけ
書き出し、すでにあればそのまま残します。

```console
$ rontolisp Server.java -o src/worker.wasm --no-wasi --host-output --optimize=size --emit-worker-entry
$ cat src/index.js
...
import module from "./worker.wasm";
import { worker } from "./worker.js";

export default worker(module);
```

`com.sun.net.httpserver` のプログラム、[Servlet アプリケーション](servlet.md)、
[WebMvc.fn アプリケーション](webmvc-fn.md)のどれも同じ手順でビルドします。あとの 2 つでは、
ライブラリをソースパスに置きます。
[`examples/cloudflare-workers/hello-servlet-one-source`](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/hello-servlet-one-source)
と
[`hello-mvcfn-one-source`](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/hello-mvcfn-one-source)
は、`examples/java` のファイルをそのまま使う Worker プロジェクトです
（`./build.sh && npx wrangler dev`）。`--no-wasi` のモジュール全般の説明は
[リアクターモジュール](../guides/wasm-gc-module.md#no-wasi-reactor-mode)にあります。`main` のない
プログラムは、代わりにメソッドをホストへエクスポートします
（[public static メソッドのリアクタ](#a-reactor-of-public-static-methods)）。

ホストはプログラムをリクエスト 1 つずつ呼び出します。プログラムの時計が進むのは呼び出しと
呼び出しの間で、1 回の呼び出しの中では進みません。そのため、ほかのターゲットと次の点が
異なります。

- ハンドラーはスレッドを開始し、その終了を待つことができます。一方、その呼び出しの中で
  プログラムのどのスレッドも終わらせられない待機（`Thread.sleep`、時間制限付きの待機、
  別のリクエストの処理を待つ待機）は、インスタンスを終了させます。呼び出しは失敗し、Worker は
  500 を返し、次のリクエストでプログラムが最初から開始されます。`System.exit` と、`main` が
  捕捉しなかった例外も、インスタンスを終了させます。
- [`--host-timer`](../guides/wasm-gc-module.md#waits-in-the-host---host-timer) を指定すると、
  Worker が JSPI を通じてプログラムを待ちます。時刻を待つ待機は Worker のタイマーで待ち、どの
  スレッドも進めないリクエストは Worker で待機し、その間 Worker は別のリクエストでプログラムを
  呼べます（非同期サーブレットのタイムアウト、別のリクエストの処理を待つリクエストなど）。
  スレッドが動くのは、リクエストを処理中の間です。
- プログラムの出力は `--host-output` があれば Worker のログになり（標準出力は `console.log`、標準エラーは
  `console.error`）、インスタンスを終わらせたものの報告もそこに出ます。なければ出力は捨てられ、ホストに見えるのは
  `RuntimeError: unreachable` だけです。`System.getenv` は `null` を返します。
  `java.util.logging` や SLF4J で書いたログは、`--wasi-logging` があればレコードのレベルに応じた
  console のメソッドへ渡ります（[ホストのログ](logging.md#the-hosts-log)）。
- `SecureRandom`（セッション ID の生成に使われます）には `--host-random` が必要です。
  `wasi:keyvalue` を通して Workers KV に置いたセッションは、インスタンスより長く残ります
  （[`wasi:keyvalue` に置くセッション](servlet.md#sessions-in-wasikeyvalue)）。
- ボディはテキストとして渡されます。`--host-boundary=streaming` を付けてビルドすると、
  オクテットのまま渡されます。
- `java.net.http` を使うプログラムは、`--host-fetch` を付けないとコンパイル時に拒否されます。
  付けると、生成された Worker は各リクエストをプラットフォームの `fetch` で送り、呼び出しは
  応答が届くまで待ちます（JSPI）。プログラムのスレッドも、ほかの応答と同じように待ちます。
- ソケット（JDBC の接続を含みます）は、`--host-sockets` を付けてビルドしない限り、開いた時点で
  `SocketException` になります。付けると、生成された Worker は接続を `cloudflare:sockets` の
  `connect()` で作ります。プログラムはほかのターゲットと同じようにソケットを待ち、その間も
  ほかのスレッドは動きます。ホストにできるのは接続だけなので、`ServerSocket` とバインドは
  `BindException` になります。ソケットは、それを開いたリクエストと一緒に終わります。次の
  リクエストまで持ち越したソケットは、そこで最初に使ったときに `SocketException` を投げます。
  `main` の実行中はソケットを待てません
  （[Worker の上の JDBC プログラム](jdbc.md#on-a-cloudflare-worker)）。

ほかのトランスポートとの違いは[言語との差異](deviations.md#the-http-server)にあります。

## ソケット

`java.net.Socket` と `ServerSocket` は、プログラム自身の TCP ソケットです。
`javax.net.SocketFactory` でも作れます。ソケットを使うプログラムは、インタプリタ、JVM、
`--native` 実行ファイル、コンポーネントで動きます。コンポーネントにはホストの
ネットワークが要ります（`wasmtime run -S inherit-network=y app.wasm`）。
素の Preview 1 モジュールにはネットワークがなく、最初のソケットで `SocketException` を
投げます。`--no-wasi` のモジュールは、`--host-sockets` を付けてビルドするとホストのソケットを
使います（[Cloudflare Worker](#a-cloudflare-worker)）。書き込みは戻る前に送られ、読み込みは届いたものを返し、`setSoTimeout` は
読み込みに期限を与えます。スタックが 1 つのターゲットでも、`accept` や読み込みで待つ
スレッドの間はプログラムのほかのスレッドが進みます。そのため、サーバーとクライアントを
1 つのプログラムのスレッドにできます。

```java
void main() throws IOException, InterruptedException {
    ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    Thread echo = new Thread(() -> {
        try (Socket peer = server.accept()) {
            BufferedReader in = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(peer.getOutputStream(), true);
            String line;
            while ((line = in.readLine()) != null) {
                out.println(line.toUpperCase());
            }
        }
        catch (IOException e) {
            IO.println(e);
        }
    });
    echo.start();
    try (Socket socket = new Socket("127.0.0.1", server.getLocalPort())) {
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        for (String word : List.of("hello", "sockets")) {
            out.println(word);
            IO.println(in.readLine());
        }
    }
    echo.join();
    server.close();
}
```

```
HELLO
SOCKETS
```

ターゲットごとの違いと、用意していないメンバーは[言語との差異](deviations.md#sockets)にあります。
JDBC に対して書いたプログラムは、一緒にコンパイルしたドライバーを使って、このソケットの上で
PostgreSQL サーバーに接続します（[PostgreSQL に JDBC でつなぐ](jdbc.md)）。

## ダイジェストと鍵

`MessageDigest`、`Mac`、`SecretKeyFactory` は、MD5、MD2、SHA-1、SHA-2 ファミリー、SHA-3、
SHAKE と、それらの HMAC、SHA-1 と SHA-2 の HMAC による PBKDF2 を、どのターゲットでも
計算します。名前を定数で書くと、その
アルゴリズムだけがコンパイル結果に入ります。`SecureRandom` の値は、ホストのエントロピー
から取ります。

```java
String hex(byte[] bytes) {
    StringBuilder text = new StringBuilder();
    for (byte b : bytes) {
        text.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
    }
    return text.toString();
}

void main() throws GeneralSecurityException {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest("abc".getBytes(StandardCharsets.UTF_8));
    IO.println(hex(digest));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    IO.println(hex(mac.doFinal("The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8))));
    PBEKeySpec spec = new PBEKeySpec("pencil".toCharArray(), "salt".getBytes(StandardCharsets.UTF_8), 4096, 256);
    IO.println(hex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded()));
}
```

```
ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8
0997564f292923271312698037b6b0a06a8be7fbd912480847c5a1ace4b8d1c7
```

使える名前と、用意していないメンバーは[言語との差異](deviations.md#digests-and-keys)にあります。

言語と動作が異なる点と、拒否される構文は[言語との差異](deviations.md)にあります。
