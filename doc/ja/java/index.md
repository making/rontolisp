# Java 言語のサブセット（実験的）

**実験的。** rontolisp は Java 言語のサブセットを読み、Common Lisp と同じコアフォームに
変換します。そのため `.java` のプログラムはどのバックエンドでも動きます。サブセットであることは
設計上の選択で、ここにあるものは互換性の保証ではありません。サブセットにない構文は、
プログラムを実行する前に、構文の名前と位置を示して拒否します。フロントエンドとライブラリは
公開されている仕様から rontolisp のために書いたものです。JDK のコードは読み込まず、同梱せず、
コンパイルしたプログラムにも含めません。

`.java` のファイルはこのサブセットとして読まれます。それ以外のファイルと `-e` では
`--source-language java` で指定します。プログラムは、開始するファイルと、そのファイルが名前を
挙げるソースツリー内のファイルです。

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
複数のパッケージからなるプログラムと、その期待される出力があります。

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
1 つのクラスのメンバーになり、ライブラリのクラスはすべて import なしで使えます。同じファイルに
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

`import` には、クラス、オンデマンドのパッケージ、`module java.base`（ライブラリのすべての
クラス）を指定できます。`static` を付けると、クラスの static メンバー 1 つ、またはオンデマンドで
そのすべてを指定できます。`public` でないクラスとアクセス修飾子のないメンバーには、同じ
パッケージからだけアクセスできます。`protected` のメンバーには、同じパッケージと、そのクラスの
サブクラスからアクセスできます。

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
そのクラスを型に含む変数は `null` になります。セッションは他のファイルを読みません。

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
このコマンドが整形するのは S 式です。Maven プラグインがコンパイルするのは `src/main/lisp` で、
`src/main/java` は引き続き Java コンパイラのものです。

## 読める構文

| | |
|---|---|
| 型 | `int`、`long`、`short`、`byte`、`char`、`double`、`float`、`boolean`、`String`、クラス、インターフェース、列挙型、レコード、配列。ローカル変数の `var`。型引数、ワイルドカード（`?`、`? extends T`、`? super T`）、raw 型 |
| 宣言 | `extends` と `implements` を持つクラス、抽象クラスと抽象メソッド。型パラメータとその境界を持つクラス、インターフェース、レコード、メソッド。フィールド、コンストラクタ、メソッド、本体付きの定数を持つ列挙型。コンパクトコンストラクタと標準コンストラクタを持つレコード。抽象メソッド、`default` メソッド、`static` メソッド、`private` メソッド、定数を持つインターフェース。`sealed`、`permits`、`non-sealed`。static なネストクラス、内部クラス、ローカルクラス、匿名クラス。フィールド、static フィールド、static 初期化ブロックとインスタンス初期化ブロック。`this(..)`、`super(..)`、`outer.super(..)`、およびその前の文を持つコンストラクタ。メソッドのオーバーロードと可変長引数。`synchronized` メソッドと `volatile` フィールド。`package` と `import` |
| 文 | ローカル変数（`final`、無名変数 `_`）、`if`、`while`、`do`、`for`、配列または `Iterable` に対する拡張 `for`、コロン形式または矢印形式の `case`・パターン・ガードを持つ `switch`、ラベル付きの文、ラベルの有無を問わない `break` と `continue`、`yield`、`return`、`throw`、`catch`（1 つまたは複数の型）と `finally` を持つ `try`、`try`-with-resources、オブジェクトまたはクラスリテラルに対する `synchronized` |
| 式 | プリミティブ型のすべての演算子とその変換、文字列連結、条件演算子、代入と複合代入、キャスト、型・型パターン・レコードパターンに対する `instanceof`、`switch` 式、`new`、クラス本体の有無を問わない `outer.new Inner()`、`Outer.this`、長さを 1 つ以上指定する配列の生成と初期化子、テキストブロック、型引数の有無を問わないメソッド呼び出し、`super.m(..)`、`Interface.super.m(..)`、`Outer.super.m(..)`、`new Box<>(..)`、任意の関数型インターフェースに対するラムダとメソッド参照（`Type::name`、`expression::name`、`super::name`、`Type::new`、`int[]::new`） |

`java.lang` のアノテーション（`@Override`、`@FunctionalInterface`、`@SuppressWarnings`、
`@SafeVarargs`、`@Deprecated`）は読み飛ばします。ただし `@Override` と `@FunctionalInterface` は
コンパイラと同じように検査します。それ以外の名前はプログラムのどのアノテーション型でもないので、
コンパイラと同じように拒否します。`int`、`long`、`short`、`byte` は 2 の補数として桁あふれします。
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

## ライブラリ

各クラスにあるメンバーは次の表のものだけです。表にないクラスとメンバーは名前を示して拒否します。
`String`、`StringBuilder`、ボックス、`Math`、`System`、`IO` はフロントエンドの一部です。
それ以外のクラスはこのサブセット自身で書かれていて、プログラムが使う部分だけがプログラムと
一緒にコンパイルされます。そのため、プログラムのクラスはライブラリのクラスを継承または実装
できます（`AbstractList`、`Iterable`、`Comparator`）。

| クラス | メンバー |
|---|---|
| `Object` | `new Object()`、`toString`、`equals`、`hashCode`、`getClass().getName()`、`getClass().getSimpleName()`、`wait()`、`wait(long)`、`notify`、`notifyAll` |
| 列挙型 | `values()`、`valueOf(String)`、`name`、`ordinal`、`compareTo`、`toString` |
| レコード | アクセサ、`equals`、`hashCode`、`toString` |
| 配列 | `length`、`clone()` |
| `String` | `length`、`charAt`、`isEmpty`、`isBlank`、`equals`、`equalsIgnoreCase`、`compareTo`、`compareToIgnoreCase`、`hashCode`、`substring`、`subSequence`、文字列または文字の `indexOf` と `lastIndexOf`、`contains`、`startsWith`、`endsWith`、`toUpperCase`、`toLowerCase`、`trim`、`strip`、`stripLeading`、`stripTrailing`、`repeat`、`replace`、`concat`、`toCharArray`、`split`、`intern`、`formatted`。`String.valueOf`、`String.join`、`String.format`。`new String()`、`new String(String)`、`new String(char[])`、`new String(char[], int, int)` |
| `StringBuilder`、`StringBuffer` | 引数なし、容量、文字列を取るコンストラクタ。`append`、`insert`、`reverse`、`setLength`、`setCharAt`、`deleteCharAt`、`delete`、`replace`、`indexOf`、`toString`、`length`、`isEmpty`、`charAt`、`substring`、`subSequence` |
| `CharSequence` | `length`、`charAt`、`subSequence`、`isEmpty`。`String` と `StringBuilder` は `CharSequence` です |
| `Integer`、`Long` | ボクシングとアンボクシング。`MAX_VALUE`、`MIN_VALUE`、基数の指定がある、またはない `parseInt` / `parseLong`、`valueOf`、基数の指定がある、またはない `toString`、`toBinaryString`、`toHexString`、`Integer.toOctalString`、`compare`、`signum`、`sum`、`max`、`min`、`bitCount`、`hashCode`、`intValue`、`longValue`、`doubleValue`、`compareTo` |
| `Byte`、`Short` | ボクシングとアンボクシング。`MAX_VALUE`、`MIN_VALUE`、基数の指定がある、またはない `parseByte` / `parseShort`、`valueOf`、`toString`、`compare`、`hashCode`、`toUnsignedInt`、`byteValue` / `shortValue`、`intValue`、`longValue`、`floatValue`、`doubleValue`、`compareTo` |
| `Float` | `MAX_VALUE`、`MIN_VALUE`、`MIN_NORMAL`、`POSITIVE_INFINITY`、`NEGATIVE_INFINITY`、`NaN`、`parseFloat`、`valueOf`、`toString`、`compare`、`hashCode`、`floatToIntBits`、`isNaN`、`isInfinite`、`isFinite`、`sum`、`max`、`min`、`floatValue`、`doubleValue`、`intValue`、`longValue`、`compareTo` |
| `Double` | `MAX_VALUE`、`MIN_VALUE`、`POSITIVE_INFINITY`、`NEGATIVE_INFINITY`、`NaN`、`parseDouble`、`valueOf`、`toString`、`compare`、`hashCode`、`doubleToLongBits`、`isNaN`、`isInfinite`、`isFinite`、`sum`、`max`、`min`、`intValue`、`longValue`、`doubleValue`、`compareTo` |
| `Boolean`、`Character` | `Boolean.TRUE`、`FALSE`、`parseBoolean`、`valueOf`、`toString`、`hashCode`、`compare`、`logicalAnd`、`logicalOr`、`logicalXor`、`booleanValue`、`compareTo`。`Character.MAX_VALUE`、`MIN_VALUE`、`valueOf`、`toString`、`compare`、`hashCode`、`isDigit`、`isLetter`、`isLetterOrDigit`、`isAlphabetic`、`isWhitespace`、`isSpaceChar`、`isUpperCase`、`isLowerCase`、`toUpperCase`、`toLowerCase`、`getNumericValue`、`digit`、`forDigit`、`charValue`、`compareTo` |
| `Number` | `intValue`、`longValue`、`floatValue`、`doubleValue`、`byteValue`、`shortValue` |
| `Math` | `PI`、`E`、`abs`、`max`、`min`、`sqrt`、`cbrt`、`pow`、`exp`、`log`、`log10`、`floor`、`ceil`、`rint`、`round`、`signum`、`sin`、`cos`、`tan`、`asin`、`acos`、`atan`、`atan2`、`sinh`、`cosh`、`tanh`、`hypot`、`toRadians`、`toDegrees`、`floorDiv`、`floorMod`、`random`、`addExact`、`subtractExact`、`multiplyExact`、`negateExact`、`toIntExact` |
| `System` | `out`、`err`（`println`、`print`、`printf`、`format`、`flush`）、`currentTimeMillis`、`nanoTime`、`exit`、`arraycopy`、`identityHashCode`、`lineSeparator` |
| `IO` | `println`、`print`、`readln` |
| `Iterable`、`Iterator` | `iterator`、`forEach`。`hasNext`、`next`、`remove`、`forEachRemaining` |
| `Collection` | `size`、`isEmpty`、`contains`、`add`、`remove`、`containsAll`、`addAll`、`removeAll`、`retainAll`、`removeIf`、`clear`、`toArray`、`iterator`、`forEach`、`equals`、`hashCode`、`toString` |
| `List`、`ArrayList` | `Collection` のメンバー。`get`、`set`、添字を指定する `add` と `addAll`、添字を指定する `remove`、`indexOf`、`lastIndexOf`、`subList`、`sort`、`replaceAll`、`getFirst`、`getLast`、`removeFirst`、`removeLast`、`addFirst`、`addLast`、`reversed`。`List.of`、`List.copyOf`。引数なし、容量、コレクションを取る `new ArrayList<>()`、`ensureCapacity`、`trimToSize` |
| `Set`、`HashSet` | `Collection` のメンバー。`Set.of`、`Set.copyOf`。引数なし、容量、コレクションを取る `new HashSet<>()` |
| `Map`、`HashMap`、`Map.Entry` | `size`、`isEmpty`、`containsKey`、`containsValue`、`get`、`getOrDefault`、`put`、`putIfAbsent`、`putAll`、`remove`、`replace`、`clear`、`keySet`、`values`、`entrySet`、`forEach`、`replaceAll`、`computeIfAbsent`、`computeIfPresent`、`compute`、`merge`、`equals`、`hashCode`、`toString`。10 組までの `Map.of`、`Map.entry`、`Map.ofEntries`、`Map.copyOf`。引数なし、容量、マップを取る `new HashMap<>()`。`getKey`、`getValue`、`setValue`、`Map.Entry.comparingByKey`、`comparingByValue` |
| `Queue`、`Deque`、`ArrayDeque` | `Collection` のメンバー。`offer`、`poll`、`peek`、`element`、`remove`。`addFirst`、`addLast`、`offerFirst`、`offerLast`、`removeFirst`、`removeLast`、`pollFirst`、`pollLast`、`getFirst`、`getLast`、`peekFirst`、`peekLast`、`push`、`pop`、`descendingIterator` |
| `AbstractCollection`、`AbstractList`、`AbstractSet`、`AbstractMap` | プログラムのクラスがコレクションになるために継承するクラス |
| `Vector`、`Stack`、`Enumeration` | `ArrayList` のメンバー。`addElement`、`elementAt`、`firstElement`、`lastElement`、`setElementAt`、`insertElementAt`、`removeElementAt`、`removeElement`、`removeAllElements`、`elements`。`push`、`pop`、`peek`、`empty`、`search`。`hasMoreElements`、`nextElement` |
| `Hashtable` | `Map` のメンバー。`keys`、`elements`、`contains`。キーにも値にも `null` は使えません |
| `Arrays` | `asList`、`toString`、`fill`、`equals`、`copyOf`、`copyOfRange`、配列全体または範囲に対する、コンパレータの指定がある、またはない `sort`、`binarySearch`、`int[]` と `Object[]` の `hashCode`。対象は `int`、`long`、`double`、`char`、`boolean`、オブジェクトの配列 |
| `Collections` | `sort`、`reverse`、`shuffle`、`swap`、`max`、`min`、`frequency`、`addAll`、`fill`、`nCopies`、`emptyList`、`emptySet`、`emptyMap`、`singletonList`、`singleton`、`unmodifiableList`、`unmodifiableSet`、`unmodifiableCollection`、`unmodifiableMap`、`reverseOrder` |
| `Objects` | `equals`、`hashCode`、`hash`、`toString`、`isNull`、`nonNull`、`requireNonNull`、`requireNonNullElse`、`compare`、`checkIndex` |
| `Optional` | `empty`、`of`、`ofNullable`、`isPresent`、`isEmpty`、`get`、`orElse`、`orElseGet`、`orElseThrow`、`ifPresent`、`ifPresentOrElse`、`filter`、`map`、`flatMap`、`or`、`equals`、`hashCode`、`toString` |
| `Random` | `new Random()`、`new Random(long)`、`setSeed`、`nextInt()`、`nextInt(int)`、`nextLong`、`nextDouble`、`nextBoolean`、`nextGaussian`。シードを指定すると仕様どおりの列になります |
| `Comparator` | `compare`、`reversed`、コンパレータまたはキーを取る `thenComparing`、`thenComparingInt`、`Comparator.naturalOrder`、`reverseOrder`、キーのコンパレータの指定がある、またはない `comparing`、`comparingInt`、`comparingLong`、`comparingDouble`、`nullsFirst`、`nullsLast` |
| 例外 | `Throwable`、`Exception`、`Error`、`RuntimeException`、`IllegalArgumentException`、`IllegalStateException`、`ArithmeticException`、`NullPointerException`、`ClassCastException`、`IndexOutOfBoundsException`、`ArrayIndexOutOfBoundsException`、`StringIndexOutOfBoundsException`、`NegativeArraySizeException`、`UnsupportedOperationException`、`NumberFormatException`、`CloneNotSupportedException`、`InterruptedException`、`IllegalMonitorStateException`、`IllegalThreadStateException`、`NoSuchElementException`、`ConcurrentModificationException`、`EmptyStackException`、`MatchException`。API のコンストラクタ（引数なし、メッセージ、メッセージと原因、原因、添字）、`getMessage`、`getLocalizedMessage`、`getCause`、`initCause`、`addSuppressed`、`getSuppressed`、`printStackTrace()`、`toString`。実行時に起きる `IllegalFormatException` とその 3 つのサブクラス（コンストラクタなし）：`IllegalFormatConversionException`、`UnknownFormatConversionException`、`MissingFormatArgumentException` |
| `Runnable`、`Comparable`、`AutoCloseable` | `run`。`compareTo`（`String`、ボックス、列挙型の定数にもあります）。`close` |
| `Thread` | 引数なし、`Runnable`、名前、その両方を取るコンストラクタ。`Thread` を継承するクラス。`start`、`run`、`join()`、`join(long)`、`interrupt`、`isInterrupted`、`isAlive`、`getName`、`setName`、`isDaemon`、`setDaemon`、`getPriority`、`setPriority`、`isVirtual`、`threadId`、`getId`、`toString`。`Thread.currentThread`、`sleep(long)`、`sleep(long, int)`、`yield`、`onSpinWait`、`interrupted`、`holdsLock`、`startVirtualThread`。`Thread.ofPlatform()` と `ofVirtual()`（`name`、`daemon`、`priority`、`start`、`unstarted`） |
| `ThreadLocal` | `new ThreadLocal<>()`、`withInitial`、`get`、`set`、`remove`、`initialValue` |
| `AtomicInteger`、`AtomicLong` | `get`、`set`、`lazySet`、`getAndSet`、`compareAndSet`、`getAndAdd`、`addAndGet`、`getAndIncrement`、`getAndDecrement`、`incrementAndGet`、`decrementAndGet`、`getAndUpdate`、`updateAndGet`、`getAndAccumulate`、`accumulateAndGet`、`intValue`、`longValue`、`doubleValue`、`toString` |
| `AtomicBoolean`、`AtomicReference` | `get`、`set`、`lazySet`、`getAndSet`、`compareAndSet`、`toString`。`AtomicReference` には `getAndUpdate`、`updateAndGet`、`getAndAccumulate`、`accumulateAndGet` もあります |
| `java.util.function` | `Function`、`BiFunction`、`UnaryOperator`、`BinaryOperator`、`Supplier`、`Consumer`、`BiConsumer`、`Predicate`、`BiPredicate`、`BooleanSupplier`。`Int`、`Long`、`Double` のそれぞれに `IntFunction`、`IntPredicate`、`IntUnaryOperator`、`IntBinaryOperator`、`IntSupplier`、`IntConsumer`、`ToIntFunction`、`ToIntBiFunction`。各インターフェースの抽象メソッドと `andThen`、`compose`、`and`、`or`、`negate`、`identity`、`not`、`isEqual` |

`String.format`、`formatted`、`printf` が受け付ける変換は `%d`、`%s`、`%S`、`%f`、`%e`、`%E`、
`%x`、`%X`、`%o`、`%c`、`%b`、`%n`、`%%` です。フラグ `-`、`0`、`+`、空白、`,`、`(`、`#` と、
幅、精度、引数の番号（`%2$s`）を指定できます。`String.split` が受け付けるのは、演算子を
含まない文字列か、1 つの文字クラス（`\\s`、`\\d`、`\\w`、`[,;]`、`[0-9]`）です。文字クラスには
`+` を続けられます。

`HashMap`、`HashSet`、`Hashtable` は、キーを `equals` と `hashCode` で探し、JDK と同じ順序で
キーをたどります。

```java
record Point(int x, int y) {
}

class Grid implements Iterable<Point> {
    private final List<Point> points = new ArrayList<>();

    Grid add(int x, int y) {
        points.add(new Point(x, y));
        return this;
    }

    public Iterator<Point> iterator() {
        return points.iterator();
    }
}

void main() {
    Map<Point, String> names = new HashMap<>();
    names.put(new Point(0, 0), "origin");
    Map<Integer, List<Point>> byRow = new HashMap<>();
    for (Point p : new Grid().add(0, 0).add(3, 1).add(1, 1)) {
        byRow.computeIfAbsent(p.y(), row -> new ArrayList<>()).add(p);
    }
    List<Point> row = byRow.get(1);
    row.sort(Comparator.comparingInt(Point::x));
    Set<String> words = new HashSet<>(Arrays.asList("to be or not to be".split(" ")));
    IO.println(names.get(new Point(0, 0)) + " " + byRow.keySet() + " " + row + " " + words);
    IO.println(String.format("%-6s|%5.2f|%04d|%x", "pi", Math.PI, 42, 255));
}
```

```
origin [0, 1] [Point[x=1, y=1], Point[x=3, y=1]] [not, be, or, to]
pi    | 3.14|0042|ff
```

```java
class Account {
    private int balance;

    void withdraw(int amount) {
        if (amount > balance) {
            throw new IllegalStateException("insufficient funds: " + (amount - balance));
        }
        balance -= amount;
    }

    void deposit(int amount) {
        balance += amount;
    }
}

void main() {
    Account account = new Account();
    account.deposit(100);
    Map<String, Integer> attempts = new HashMap<>();
    for (int amount : new int[] {30, 100, 50}) {
        try {
            account.withdraw(amount);
            attempts.put("ok", attempts.getOrDefault("ok", 0) + 1);
        } catch (IllegalStateException e) {
            IO.println(e.getMessage());
            attempts.put("refused", attempts.getOrDefault("refused", 0) + 1);
        } finally {
            IO.println("tried " + amount);
        }
    }
    IO.println(attempts.get("ok") + " ok, " + attempts.get("refused") + " refused, " + attempts.get("other"));
}
```

```
tried 30
insufficient funds: 30
tried 100
tried 50
2 ok, 1 refused, null
```

## スレッド

`Thread` の開始、`join`、割り込み、名前は言語仕様のとおりに動きます。`synchronized` は
オブジェクトのモニターを保持し、`wait` と `notify` はモニターをスレッドからスレッドへ
受け渡します。`volatile` フィールドを読むと、最後に書き込まれた値が得られます。スレッドが
捕捉しなかった例外は、スレッド名とともに標準エラーに書き出され、そのスレッドだけが
終了します。プログラムは、デーモンでないスレッドがすべて終了したときに終了します。

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
開始されたスレッドは、実行中のスレッドが待機、スリープ、`yield`、終了のいずれかをしたときに
動きます。スレッドを `join` し、共有するデータをモニターで保護しているプログラムは、
どのバックエンドでも同じ出力になります。それ以外のプログラムでスタックが 1 本であることから
生じる違いは[言語との差異](deviations.md#threads)にあります。

言語と動作が異なる点と、拒否される構文は[言語との差異](deviations.md)にあります。
