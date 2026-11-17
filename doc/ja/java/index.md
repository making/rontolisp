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
REPL はありません。プログラムを与えずに `--source-language java` を指定するとエラーになります。

[`examples/java/`](https://github.com/making/rontolisp/tree/develop/examples/java) に、
期待される出力を添えた実行可能なプログラムがあります。

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
`rontolisp app/Main.java` と同じようにプログラムを実行します。

```console
$ rontolisp app/Main.java
$ rontolisp app/Main.java -o main.wasm
```

`import` には、クラス、オンデマンドのパッケージ、`module java.base`（ライブラリのすべての
クラス）を指定できます。`static` を付けると、クラスの static メンバー 1 つ、またはオンデマンドで
そのすべてを指定できます。`public` でないクラスとアクセス修飾子のないメンバーには、同じ
パッケージからだけアクセスできます。`protected` のメンバーには、同じパッケージと、そのクラスの
サブクラスからアクセスできます。

## 読める構文

| | |
|---|---|
| 型 | `int`、`long`、`short`、`byte`、`char`、`double`、`float`、`boolean`、`String`、クラス、インターフェース、列挙型、レコード、配列。ローカル変数の `var`。型引数、ワイルドカード（`?`、`? extends T`、`? super T`）、raw 型 |
| 宣言 | `extends` と `implements` を持つクラス、抽象クラスと抽象メソッド。型パラメータとその境界を持つクラス、インターフェース、レコード、メソッド。フィールド、コンストラクタ、メソッド、本体付きの定数を持つ列挙型。コンパクトコンストラクタと標準コンストラクタを持つレコード。抽象メソッド、`default` メソッド、`static` メソッド、`private` メソッド、定数を持つインターフェース。`sealed`、`permits`、`non-sealed`。static なネストクラス、内部クラス、ローカルクラス、匿名クラス。フィールド、static フィールド、static 初期化ブロックとインスタンス初期化ブロック。`this(..)` と `super(..)`、およびその前の文を持つコンストラクタ。メソッドのオーバーロードと可変長引数。`package` と `import` |
| 文 | ローカル変数（`final`、無名変数 `_`）、`if`、`while`、`do`、`for`、配列または `Iterable` に対する拡張 `for`、コロン形式または矢印形式の `case`・パターン・ガードを持つ `switch`、ラベル付きの文、ラベルの有無を問わない `break` と `continue`、`yield`、`return`、`throw`、`catch`（1 つまたは複数の型）と `finally` を持つ `try`、`try`-with-resources |
| 式 | プリミティブ型のすべての演算子とその変換、文字列連結、条件演算子、代入と複合代入、キャスト、型・型パターン・レコードパターンに対する `instanceof`、`switch` 式、`new`、`outer.new Inner()`、`Outer.this`、長さを 1 つ以上指定する配列の生成と初期化子、テキストブロック、型引数の有無を問わないメソッド呼び出し、`super.m(..)`、`new Box<>(..)`、任意の関数型インターフェースに対するラムダとメソッド参照（`Type::name`、`expression::name`、`super::name`、`Type::new`、`int[]::new`） |

アノテーションは読み飛ばします。`int`、`long`、`short`、`byte` は 2 の補数として桁あふれします。
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
| `Object` | `new Object()`、`toString`、`equals`、`hashCode`、`getClass().getName()`、`getClass().getSimpleName()` |
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
| 例外 | `Throwable`、`Exception`、`Error`、`RuntimeException`、`IllegalArgumentException`、`IllegalStateException`、`ArithmeticException`、`NullPointerException`、`ClassCastException`、`IndexOutOfBoundsException`、`ArrayIndexOutOfBoundsException`、`StringIndexOutOfBoundsException`、`NegativeArraySizeException`、`UnsupportedOperationException`、`NumberFormatException`、`CloneNotSupportedException`、`InterruptedException`、`NoSuchElementException`、`ConcurrentModificationException`、`EmptyStackException`、`MatchException`。API のコンストラクタ（引数なし、メッセージ、メッセージと原因、原因、添字）、`getMessage`、`getLocalizedMessage`、`getCause`、`initCause`、`addSuppressed`、`getSuppressed`、`printStackTrace()`、`toString`。実行時に起きる `IllegalFormatException` とその 3 つのサブクラス（コンストラクタなし）：`IllegalFormatConversionException`、`UnknownFormatConversionException`、`MissingFormatArgumentException` |
| `Runnable`、`Comparable`、`AutoCloseable` | `run`。`compareTo`（`String`、ボックス、列挙型の定数にもあります）。`close` |
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

言語と動作が異なる点と、拒否される構文は[言語との差異](deviations.md)にあります。
