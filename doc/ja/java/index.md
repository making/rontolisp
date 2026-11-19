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
複数のパッケージからなるプログラムとその期待される出力、および HTTP サーバーとその
クライアントがあります。

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
| 型 | `int`、`long`、`short`、`byte`、`char`、`double`、`float`、`boolean`、`String`、クラス、インターフェース、列挙型、レコード、配列。ローカル変数の `var`。型引数、ワイルドカード（`?`、`? extends T`、`? super T`）、raw 型。型パラメータを持つクラスの内部クラス（`Outer<String>.Inner`） |
| 宣言 | `extends` と `implements` を持つクラス、抽象クラスと抽象メソッド。型パラメータとその境界を持つクラス、インターフェース、レコード、メソッド。フィールド、コンストラクタ、メソッド、本体付きの定数を持つ列挙型。コンパクトコンストラクタと標準コンストラクタを持つレコード。抽象メソッド、`default` メソッド、`static` メソッド、`private` メソッド、定数を持つインターフェース。`sealed`、`permits`、`non-sealed`。static なネストクラス、内部クラス、ローカルクラス、匿名クラス。フィールド、static フィールド、上位クラスのフィールドを隠すフィールド、static 初期化ブロックとインスタンス初期化ブロック。`this(..)`、`super(..)`、`outer.super(..)`、およびその前の文を持つコンストラクタ。メソッドのオーバーロードと可変長引数。`synchronized` メソッドと `volatile` フィールド。`package` と `import` |
| 文 | ローカル変数（`final`、無名変数 `_`）、`if`、`while`、`do`、`for`、配列または `Iterable` に対する拡張 `for`、コロン形式または矢印形式の `case`・パターン・ガードを持つ `switch`、ラベル付きの文、ラベルの有無を問わない `break` と `continue`、`yield`、`return`、`throw`、`catch`（1 つまたは複数の型）と `finally` を持つ `try`、`try`-with-resources、オブジェクトまたはクラスリテラルに対する `synchronized` |
| 式 | プリミティブ型のすべての演算子とその変換、文字列連結、条件演算子、代入と複合代入、キャスト（複数の型への `(A & B) x` も、ラムダとメソッド参照のものも）、型・型パターン・レコードパターンに対する `instanceof`、`switch` 式、コンストラクタの型引数の有無を問わない `new`（`new <T>Box(..)`）、クラス本体の有無を問わない `outer.new Inner()`、`Outer.this`、長さを 1 つ以上指定する配列の生成と初期化子、テキストブロック、型引数の有無を問わないメソッド呼び出し、`super.m(..)`、フィールドを読み書きする `super.x`、`Interface.super.m(..)`、`Outer.super.m(..)`、クラス本体の有無を問わない `new Box<>(..)`、任意の関数型インターフェースに対するラムダとメソッド参照（`Type::name`、`expression::name`、`super::name`、`Type::new`、`int[]::new`、`List<String>::size`、`Type::<T>name`、`Type::<T>new`） |

`java.lang` のアノテーション（`@Override`、`@FunctionalInterface`、`@SuppressWarnings`、
`@SafeVarargs`、`@Deprecated`）は読み飛ばします。ただし `@Override` と `@FunctionalInterface` は
コンパイラと同じように検査し、`@SuppressWarnings("unchecked")` と `@SafeVarargs` は下記の警告を
抑止します。それ以外の名前はプログラムのどのアノテーション型でもないので、
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

## ライブラリ

各クラスにあるメンバーは次の表のものだけです。表にないクラスとメンバーは名前を示して拒否します。
`String`、`StringBuilder`、ボックス、`Math`、`System`、`IO` はフロントエンドの一部です。
それ以外のクラスはこのサブセット自身で書かれていて、プログラムが使う部分だけがプログラムと
一緒にコンパイルされます。そのため、プログラムのクラスはライブラリのクラスを継承または実装
できます（`AbstractList`、`Iterable`、`Comparator`）。

| クラス | メンバー |
|---|---|
| `Object` | `new Object()`、`toString`、`equals`、`hashCode`、`getClass().getName()`、`getClass().getSimpleName()`、`getClass()` とクラスリテラル（`Name.class`）どうしの `==`、`!=`、`equals` による比較、クラスリテラルの `getName()` と `getSimpleName()`、`wait()`、`wait(long)`、`notify`、`notifyAll` |
| 列挙型 | `values()`、`valueOf(String)`、`name`、`ordinal`、`compareTo`、`toString` |
| レコード | アクセサ、`equals`、`hashCode`、`toString` |
| 配列 | `length`、`clone()` |
| `String` | `length`、`charAt`、`isEmpty`、`isBlank`、`equals`、`equalsIgnoreCase`、`compareTo`、`compareToIgnoreCase`、`hashCode`、`substring`、`subSequence`、開始位置の指定がある、またはない、文字列または文字の `indexOf` と `lastIndexOf`、範囲を取る `indexOf`、`codePointAt`、`codePointBefore`、`codePointCount`、`offsetByCodePoints`、`regionMatches`、`contentEquals`、`getChars`、`indent`、`contains`、`startsWith`、`endsWith`、`toUpperCase`、`toLowerCase`、`trim`、`strip`、`stripLeading`、`stripTrailing`、`repeat`、`replace`、`replaceAll`、`replaceFirst`、`matches`、`concat`、`toCharArray`、`split`、`intern`、`formatted`、引数なし、`Charset`、文字セットの名前を取る `getBytes`、`Locale` を取る `toUpperCase` と `toLowerCase`。`String.valueOf`、`String.copyValueOf`、`String.join`、`Locale` の指定がある、またはない `String.format`、`String.CASE_INSENSITIVE_ORDER`。`new String()`、`new String(String)`、`new String(char[])`、`new String(char[], int, int)`、範囲と、`Charset` または文字セットの名前を指定できる `new String(byte[])` |
| `StringBuilder`、`StringBuffer` | 引数なし、容量、文字列を取るコンストラクタ。値、または文字列か `char[]` の範囲を取る `append`、`insert`、`reverse`、`setLength`、`setCharAt`、`deleteCharAt`、`delete`、`replace`、開始位置の指定がある、またはない `indexOf` と `lastIndexOf`、`appendCodePoint`、文字列またはコードポイントを取る `repeat`、`compareTo`、`toString`、`length`、`isEmpty`、`charAt`、`substring`、`subSequence` |
| `CharSequence` | `length`、`charAt`、`subSequence`、`isEmpty`。`String` と `StringBuilder` は `CharSequence` です |
| `Appendable` | `CharSequence`、その範囲、`char` を取る `append`。`StringBuilder`、`Writer`、`PrintStream` は `Appendable` です |
| `Integer`、`Long` | ボクシングとアンボクシング。`MAX_VALUE`、`MIN_VALUE`、`SIZE`、`BYTES`、基数の指定がある、またはない `parseInt` / `parseLong`、`valueOf`、`decode`、基数の指定がある、またはない `toString`、`toBinaryString`、`toHexString`、`toOctalString`、基数の指定がある、またはない `Integer.parseUnsignedInt` と `Long.parseUnsignedLong`、基数の指定がある、またはない `toUnsignedString`、`Integer.toUnsignedLong`、`divideUnsigned`、`remainderUnsigned`、`compareUnsigned`、`highestOneBit`、`lowestOneBit`、`numberOfLeadingZeros`、`numberOfTrailingZeros`、`reverse`、`reverseBytes`、`rotateLeft`、`rotateRight`、`compare`、`signum`、`sum`、`max`、`min`、`bitCount`、`hashCode`、`intValue`、`longValue`、`doubleValue`、`compareTo` |
| `Byte`、`Short` | ボクシングとアンボクシング。`MAX_VALUE`、`MIN_VALUE`、`SIZE`、`BYTES`、基数の指定がある、またはない `parseByte` / `parseShort`、`valueOf`、`toString`、`compare`、`hashCode`、`toUnsignedInt`、`byteValue` / `shortValue`、`intValue`、`longValue`、`floatValue`、`doubleValue`、`compareTo` |
| `Float` | `MAX_VALUE`、`MIN_VALUE`、`MIN_NORMAL`、`MAX_EXPONENT`、`MIN_EXPONENT`、`SIZE`、`BYTES`、`POSITIVE_INFINITY`、`NEGATIVE_INFINITY`、`NaN`、`parseFloat`、`valueOf`、`toString`、`toHexString`、`compare`、`hashCode`、`floatToIntBits`、`floatToRawIntBits`、`intBitsToFloat`、`isNaN`、`isInfinite`、`isFinite`、`sum`、`max`、`min`、`floatValue`、`doubleValue`、`intValue`、`longValue`、`compareTo` |
| `Double` | `MAX_VALUE`、`MIN_VALUE`、`MIN_NORMAL`、`MAX_EXPONENT`、`MIN_EXPONENT`、`SIZE`、`BYTES`、`POSITIVE_INFINITY`、`NEGATIVE_INFINITY`、`NaN`、`parseDouble`、`valueOf`、`toString`、`toHexString`、`compare`、`hashCode`、`doubleToLongBits`、`doubleToRawLongBits`、`longBitsToDouble`、`isNaN`、`isInfinite`、`isFinite`、`sum`、`max`、`min`、`intValue`、`longValue`、`doubleValue`、`compareTo` |
| `Boolean`、`Character` | `Boolean.TRUE`、`FALSE`、`parseBoolean`、`valueOf`、`toString`、`hashCode`、`compare`、`logicalAnd`、`logicalOr`、`logicalXor`、`booleanValue`、`compareTo`。`Character.MAX_VALUE`、`MIN_VALUE`、`valueOf`、`toString`、`compare`、`hashCode`、`isDigit`、`isLetter`、`isLetterOrDigit`、`isAlphabetic`、`isWhitespace`、`isSpaceChar`、`isUpperCase`、`isLowerCase`、`toUpperCase`、`toLowerCase`、`getNumericValue`、`digit`、`forDigit`、`charValue`、`compareTo`。`getType` と一般カテゴリの定数、`isTitleCase`、`isDefined`、`isMirrored`、`isIdeographic`、`isISOControl`、`isJavaIdentifierStart`、`isJavaIdentifierPart`、`isUnicodeIdentifierStart`、`isUnicodeIdentifierPart`、`isIdentifierIgnorable`、`isEmoji`、`isEmojiPresentation`、`isEmojiModifier`、`isEmojiModifierBase`、`isEmojiComponent`、`isExtendedPictographic`。判定はどれも `char` とコードポイントのどちらも取ります。書き込み先の配列の指定がある、またはない、コードポイントを取る `toChars`、配列を取る `codePointAt`、`reverseBytes`、コードポイントを取る `toString`、`charCount`、`isValidCodePoint`、`isBmpCodePoint`、`isSupplementaryCodePoint`、`isHighSurrogate`、`isLowSurrogate`、`isSurrogate`、`isSurrogatePair`、`toCodePoint`、`highSurrogate`、`lowSurrogate`。`MIN_RADIX`、`MAX_RADIX`、`SIZE`、`BYTES`、`MIN_CODE_POINT`、`MAX_CODE_POINT`、`MIN_SUPPLEMENTARY_CODE_POINT` とサロゲートの範囲の定数 |
| `Number` | `intValue`、`longValue`、`floatValue`、`doubleValue`、`byteValue`、`shortValue` |
| `Math`、`StrictMath` | `PI`、`E`、`TAU`、`abs`、`max`、`min`、`sqrt`、`cbrt`、`pow`、`exp`、`log`、`log10`、`floor`、`ceil`、`rint`、`round`、`signum`、`sin`、`cos`、`tan`、`asin`、`acos`、`atan`、`atan2`、`sinh`、`cosh`、`tanh`、`hypot`、`toRadians`、`toDegrees`、`floorDiv`、`floorMod`、`random`、`addExact`、`subtractExact`、`multiplyExact`、`negateExact`、`toIntExact`、`absExact`、`incrementExact`、`decrementExact`、`divideExact`、`floorDivExact`、`ceilDivExact`、`ceilDiv`、`ceilMod`、`clamp`、`multiplyFull`、`multiplyHigh`、`unsignedMultiplyHigh`、`log1p`、`expm1`、`IEEEremainder`、`copySign`、`getExponent`、`nextAfter`、`ulp`、`nextUp`、`nextDown`、`fma`、`scalb` |
| `System` | `out`、`err`（どちらも `PrintStream`）、`in`（`InputStream`）、`currentTimeMillis`、`nanoTime`、`exit`、`arraycopy`、`identityHashCode`、`lineSeparator`、既定値の指定がある、またはない `getProperty`、名前を取る `getenv`、`gc` |
| `Runtime` | `Runtime.getRuntime`、`availableProcessors`、`gc`、`exit`、`halt` |
| `IO` | `println`、`print`、`readln` |
| `InputStream`、`OutputStream` | 1 バイトまたは配列への `read`、`readAllBytes`、`readNBytes`、`skip`、`skipNBytes`、`available`、`mark`、`reset`、`markSupported`、`transferTo`、`close`、`InputStream.nullInputStream`。1 バイトまたは配列の `write`、`flush`、`close`、`OutputStream.nullOutputStream`。プログラムのクラスはどちらも継承できます |
| `ByteArrayInputStream`、`ByteArrayOutputStream` | `byte[]` またはその範囲を対象にします。`toByteArray`、`size`、`reset`、`writeTo`、`writeBytes`、文字セットの指定がある、またはない `toString` |
| `FilterInputStream`、`FilterOutputStream`、`BufferedInputStream`、`BufferedOutputStream` | ほかのストリームの上に作るストリーム。バッファ付きのものはサイズを指定できます |
| `PushbackInputStream`、`SequenceInputStream` | サイズを指定できます。1 バイトまたは配列の `unread`。2 つのストリーム、またはストリームの `Enumeration` の上に作ります |
| `DataInput`、`DataOutput`、`DataInputStream`、`DataOutputStream` | `readBoolean`、`readByte`、`readUnsignedByte`、`readShort`、`readUnsignedShort`、`readChar`、`readInt`、`readLong`、`readFloat`、`readDouble`、`readFully`、`skipBytes`、`readLine`、`readUTF`。それぞれに対応する `write`、`writeBytes`、`writeChars`、`writeUTF`、`size` |
| `PrintStream` | `OutputStream` の上に作ります。自動フラッシュと文字セットを指定できます。各型の `print` と `println`、`Locale` の指定がある、またはない `printf` と `format`、`append`、`write`、`writeBytes`、`flush`、`close`、`checkError`、`charset` |
| `Reader`、`Writer` | 1 文字または配列への `read`、`skip`、`ready`、`mark`、`reset`、`markSupported`、`transferTo`、`close`。1 文字、配列、文字列の `write`、`append`、`flush`、`close`。`Reader.nullReader`、`Writer.nullWriter`。プログラムのクラスはどちらも継承できます |
| `FilterReader`、`FilterWriter`、`CharArrayReader`、`CharArrayWriter`、`PushbackReader`、`LineNumberReader` | ほかのリーダーやライターの上に作るもの。`char[]` またはその範囲を対象にするもの（`toCharArray`、`size`、`reset`、`writeTo`）。サイズを指定でき、1 文字または配列を `unread` できるもの。`getLineNumber`、`setLineNumber` |
| `StringReader`、`StringWriter`、`InputStreamReader`、`OutputStreamWriter` | 文字列を対象にします。`getBuffer`、`toString`。ストリームを対象にするものは `Charset` または文字セットの名前を指定できます。`getEncoding` |
| `BufferedReader`、`BufferedWriter`、`PrintWriter` | バッファのサイズを指定できます。`readLine`。`newLine`。`PrintWriter` は `Writer` または `OutputStream` の上に作り、自動フラッシュと文字セットを指定できます。メンバーは `PrintStream` の文字版です |
| `Charset`、`StandardCharsets` | `UTF_8`、`ISO_8859_1`、`US_ASCII`、`UTF_16`、`UTF_16BE`、`UTF_16LE`。`Charset.forName`、`defaultCharset`、`isSupported`、`name`、`displayName`、`toString` |
| `Locale` | `ROOT`、`ENGLISH`、`US`、`UK`、`CANADA`、`Locale.getDefault`（`US` を返します）、`getLanguage`、`getCountry`、`toLanguageTag`、`toString` |
| `Iterable`、`Iterator` | `iterator`、`forEach`。`hasNext`、`next`、`remove`、`forEachRemaining` |
| `Collection` | `size`、`isEmpty`、`contains`、`add`、`remove`、`containsAll`、`addAll`、`removeAll`、`retainAll`、`removeIf`、`clear`、引数なし、配列、または配列を作る関数を取る `toArray`、`iterator`、`forEach`、`equals`、`hashCode`、`toString` |
| `List`、`ArrayList` | `Collection` のメンバー。`get`、`set`、添字を指定する `add` と `addAll`、添字を指定する `remove`、`indexOf`、`lastIndexOf`、`subList`、`sort`、`replaceAll`、`getFirst`、`getLast`、`removeFirst`、`removeLast`、`addFirst`、`addLast`、`reversed`、添字の指定がある、またはない `listIterator`（`ListIterator` のメンバーは `hasNext`、`next`、`hasPrevious`、`previous`、`nextIndex`、`previousIndex`、`remove`、`set`、`add`）。`List.of`、`List.copyOf`。引数なし、容量、コレクションを取る `new ArrayList<>()`、`ensureCapacity`、`trimToSize` |
| `Set`、`HashSet` | `Collection` のメンバー。`Set.of`、`Set.copyOf`。引数なし、容量、コレクションを取る `new HashSet<>()` |
| `Map`、`HashMap`、`Map.Entry` | `size`、`isEmpty`、`containsKey`、`containsValue`、`get`、`getOrDefault`、`put`、`putIfAbsent`、`putAll`、`remove`、`replace`、`clear`、`keySet`、`values`、`entrySet`、`forEach`、`replaceAll`、`computeIfAbsent`、`computeIfPresent`、`compute`、`merge`、`equals`、`hashCode`、`toString`。10 組までの `Map.of`、`Map.entry`、`Map.ofEntries`、`Map.copyOf`。引数なし、容量、マップを取る `new HashMap<>()`。`getKey`、`getValue`、`setValue`、`Map.Entry.comparingByKey`、`comparingByValue`。キーと値、またはエントリを取る `AbstractMap.SimpleEntry` と `SimpleImmutableEntry` |
| `Queue`、`Deque`、`ArrayDeque` | `Collection` のメンバー。`offer`、`poll`、`peek`、`element`、`remove`。`addFirst`、`addLast`、`offerFirst`、`offerLast`、`removeFirst`、`removeLast`、`pollFirst`、`pollLast`、`getFirst`、`getLast`、`peekFirst`、`peekLast`、`push`、`pop`、`descendingIterator` |
| `AbstractCollection`、`AbstractList`、`AbstractSet`、`AbstractMap` | プログラムのクラスがコレクションになるために継承するクラス |
| `Vector`、`Stack`、`Enumeration` | `ArrayList` のメンバー。`addElement`、`elementAt`、`firstElement`、`lastElement`、`setElementAt`、`insertElementAt`、`removeElementAt`、`removeElement`、`removeAllElements`、`elements`。`push`、`pop`、`peek`、`empty`、`search`。`hasMoreElements`、`nextElement` |
| `Hashtable` | `Map` のメンバー。`keys`、`elements`、`contains`。キーにも値にも `null` は使えません |
| `Arrays` | `asList`、`toString`、`fill`、`equals`、`copyOf`、`copyOfRange`、配列全体または範囲に対する、コンパレータの指定がある、またはない `sort`、`binarySearch`。対象は `int`、`long`、`double`、`char`、`boolean`、オブジェクトの配列。`byte[]` には `toString`、`fill`、`equals`、`copyOf`、`copyOfRange`。`short[]` と `float[]` には `toString` と `equals`。`hashCode` はすべての配列。`int[]`、`long[]`、`double[]`、オブジェクトの配列の `setAll`。`deepToString`、`deepEquals`、`deepHashCode` |
| `Collections` | `sort`、コンパレータの指定がある、またはない `binarySearch`、`reverse`、`shuffle`、`swap`、`max`、`min`、`frequency`、`addAll`、`fill`、`nCopies`、`emptyList`、`emptySet`、`emptyMap`、`singletonList`、`singleton`、`unmodifiableList`、`unmodifiableSet`、`unmodifiableCollection`、`unmodifiableMap`、`synchronizedList`、`synchronizedSet`、`synchronizedCollection`、`synchronizedMap`、`reverseOrder`、`rotate`、`replaceAll`、`indexOfSubList`、`lastIndexOfSubList` |
| `Objects` | `equals`、`hashCode`、`hash`、`toString`、`isNull`、`nonNull`、メッセージまたはメッセージのサプライヤの指定がある、またはない `requireNonNull`、`requireNonNullElse`、`requireNonNullElseGet`、`compare`、`checkIndex`、`checkFromIndexSize`、`checkFromToIndex` |
| `Optional` | `empty`、`of`、`ofNullable`、`isPresent`、`isEmpty`、`get`、`orElse`、`orElseGet`、`orElseThrow`、`ifPresent`、`ifPresentOrElse`、`filter`、`map`、`flatMap`、`or`、`equals`、`hashCode`、`toString` |
| `OptionalLong` | `empty`、`of`、`isPresent`、`isEmpty`、`getAsLong`、`orElse`、`orElseGet`、`orElseThrow`、`ifPresent`、`ifPresentOrElse`、`equals`、`hashCode`、`toString` |
| `Random` | `new Random()`、`new Random(long)`、`setSeed`、`nextInt()`、`nextInt(int)`、`nextLong`、`nextDouble`、`nextBoolean`、`nextGaussian`。シードを指定すると仕様どおりの列になります |
| `Comparator` | `compare`、`reversed`、コンパレータ、キー、またはキーとそのコンパレータを取る `thenComparing`、`thenComparingInt`、`thenComparingLong`、`thenComparingDouble`、`Comparator.naturalOrder`、`reverseOrder`、キーのコンパレータの指定がある、またはない `comparing`、`comparingInt`、`comparingLong`、`comparingDouble`、`nullsFirst`、`nullsLast` |
| 例外 | `Throwable`、`Exception`、`Error`、`RuntimeException`、`IllegalArgumentException`、`IllegalStateException`、`ArithmeticException`、`NullPointerException`、`ClassCastException`、`IndexOutOfBoundsException`、`ArrayIndexOutOfBoundsException`、`StringIndexOutOfBoundsException`、`NegativeArraySizeException`、`UnsupportedOperationException`、`NumberFormatException`、`CloneNotSupportedException`、`InterruptedException`、`IllegalMonitorStateException`、`IllegalThreadStateException`、`NoSuchElementException`、`InputMismatchException`、`ConcurrentModificationException`、`EmptyStackException`、`MatchException`、`ArrayStoreException`、`SecurityException`、`AssertionError`、`VirtualMachineError`、`StackOverflowError`、`OutOfMemoryError`、`LinkageError`、`NoClassDefFoundError`、`ExceptionInInitializerError`（`getException` を含む）、`IOException`、`EOFException`、`FileNotFoundException`、`UTFDataFormatException`、`UnsupportedEncodingException`、`UncheckedIOException`、`UnsupportedCharsetException`、`IllegalCharsetNameException`。API のコンストラクタ（引数なし、メッセージ、メッセージと原因、原因、添字）、`getMessage`、`getLocalizedMessage`、`getCause`、`initCause`、`addSuppressed`、`getSuppressed`、`printStackTrace()`、`getStackTrace`、`setStackTrace`、`fillInStackTrace`、`toString`。`StackTraceElement` には 4 引数のコンストラクタ、`getClassName`、`getMethodName`、`getFileName`、`getLineNumber`、`isNativeMethod`。実行時に起きる `IllegalFormatException` とその 3 つのサブクラス（コンストラクタなし）：`IllegalFormatConversionException`、`UnknownFormatConversionException`、`MissingFormatArgumentException`。`java.util.concurrent` の `ExecutionException`、`TimeoutException`、`CancellationException`、`RejectedExecutionException`、`CompletionException`、`BrokenBarrierException` |
| `Runnable`、`Comparable`、`AutoCloseable`、`Closeable`、`Flushable` | `run`。`compareTo`（`String`、ボックス、列挙型の定数にもあります）。`close`。`flush` |
| `Thread` | 引数なし、`Runnable`、名前、その両方を取るコンストラクタ。`Thread` を継承するクラス。`start`、`run`、`join()`、`join(long)`、`interrupt`、`isInterrupted`、`isAlive`、`getName`、`setName`、`isDaemon`、`setDaemon`、`getPriority`、`setPriority`、`isVirtual`、`threadId`、`getId`、`toString`。`Thread.currentThread`、`sleep(long)`、`sleep(long, int)`、`sleep(Duration)`、`yield`、`onSpinWait`、`interrupted`、`holdsLock`、`startVirtualThread`。`Thread.ofPlatform()` と `ofVirtual()`（`name`、`daemon`、`priority`、`start`、`unstarted`、`factory`）。`getState` と `Thread.State`。`setUncaughtExceptionHandler`、`getUncaughtExceptionHandler`、`Thread.setDefaultUncaughtExceptionHandler`、`getDefaultUncaughtExceptionHandler` |
| `ThreadLocal`、`InheritableThreadLocal` | `new ThreadLocal<>()`、`withInitial`、`get`、`set`、`remove`、`initialValue`。`childValue` |
| `AtomicInteger`、`AtomicLong` | `get`、`set`、`lazySet`、`getAndSet`、`compareAndSet`、`getAndAdd`、`addAndGet`、`getAndIncrement`、`getAndDecrement`、`incrementAndGet`、`decrementAndGet`、`getAndUpdate`、`updateAndGet`、`getAndAccumulate`、`accumulateAndGet`、`intValue`、`longValue`、`doubleValue`、`toString` |
| `AtomicBoolean`、`AtomicReference` | `get`、`set`、`lazySet`、`getAndSet`、`compareAndSet`、`toString`。`AtomicReference` には `getAndUpdate`、`updateAndGet`、`getAndAccumulate`、`accumulateAndGet` もあります |
| `AtomicIntegerArray`、`AtomicLongArray`、`AtomicReferenceArray`、`LongAdder` | 長さ、または配列を取るコンストラクタ。値が 1 つのアトミッククラスと同じメンバー（いずれも添字を取ります）、`length`、`toString`。`add`、`increment`、`decrement`、`sum`、`reset`、`sumThenReset`、`intValue`、`longValue`、`doubleValue`、`toString` |
| `LongAccumulator`、`DoubleAdder`、`DoubleAccumulator` | 関数とその単位元を取るコンストラクタ。`accumulate`、`get`、`reset`、`getThenReset`。`add`、`sum`、`reset`、`sumThenReset`。`intValue`、`longValue`、`floatValue`、`doubleValue`、`toString` |
| `TimeUnit` | 7 つの単位。`convert`、`toNanos`、`toMicros`、`toMillis`、`toSeconds`、`toMinutes`、`toHours`、`toDays`、`sleep`、`timedJoin`、`timedWait` |
| `Lock`、`ReentrantLock`、`Condition` | `lock`、`lockInterruptibly`、制限時間の指定がある、またはない `tryLock`、`unlock`、`newCondition`、`isLocked`、`isHeldByCurrentThread`、`getHoldCount`、`isFair`、`hasQueuedThreads`、`getQueueLength`、`toString`。制限時間の指定がある、またはない `await`、`awaitNanos`、`awaitUninterruptibly`、`signal`、`signalAll` |
| `ReadWriteLock`、`ReentrantReadWriteLock` | `readLock` と `writeLock`（どちらも `Lock` で、書き込みロックは条件変数を作れます）。`getReadLockCount`、`getReadHoldCount`、`getWriteHoldCount`、`isWriteLocked`、`isWriteLockedByCurrentThread`、`isFair`、`hasQueuedThreads`、`getQueueLength`、`toString` |
| `StampedLock` | `writeLock`、`readLock`（どちらにも `Interruptibly` 版と、制限時間の指定がある、またはない `try..` 版があります）。`tryOptimisticRead`、`validate`。`unlockWrite`、`unlockRead`、`unlock`、`tryUnlockWrite`、`tryUnlockRead`。`tryConvertToWriteLock`、`tryConvertToReadLock`、`tryConvertToOptimisticRead`。`isWriteLocked`、`isReadLocked`、`getReadLockCount`、`isLockStamp`、`isWriteLockStamp`、`isReadLockStamp`、`isOptimisticReadStamp`。`asReadLock`、`asWriteLock`、`asReadWriteLock`。`toString`。スタンプの値は JDK 25 が返す値と同じです |
| `CountDownLatch`、`Semaphore` | 制限時間の指定がある、またはない `await`、`countDown`、`getCount`。`acquire`、`acquireUninterruptibly`、制限時間の指定がある、またはない `tryAcquire`、`release`（いずれも許可 1 つ、または複数）、`availablePermits`、`drainPermits`、`isFair`。どちらにも `toString` があります |
| `CyclicBarrier`、`Exchanger` | スレッド数を取り、アクションの指定がある、またはないコンストラクタ。制限時間の指定がある、またはない `await`、`reset`、`isBroken`、`getParties`、`getNumberWaiting`。制限時間の指定がある、またはない `exchange` |
| `Phaser` | 引数なし、またはパーティ数、親、その両方を取るコンストラクタ。`register`、`bulkRegister`、`arrive`、`arriveAndDeregister`、`arriveAndAwaitAdvance`、`awaitAdvance`、制限時間の指定がある、またはない `awaitAdvanceInterruptibly`、`forceTermination`、`getPhase`、`getRegisteredParties`、`getArrivedParties`、`getUnarrivedParties`、`getParent`、`getRoot`、`isTerminated`、`toString`。オーバーライドできる `onAdvance` |
| `BlockingQueue`、`ArrayBlockingQueue`、`LinkedBlockingQueue`、`SynchronousQueue`、`ConcurrentLinkedQueue` | `Queue` のメンバー。ブロッキングキューには `put`、`take`、制限時間を指定する `offer` と `poll`、`remainingCapacity`、`drainTo` もあります。`SynchronousQueue` は要素を保持せず、要素を入れるスレッドから取り出すスレッドへ直接渡します |
| `TransferQueue`、`LinkedTransferQueue` | ブロッキングキューのメンバー。`transfer`、制限時間の指定がある、またはない `tryTransfer`、`hasWaitingConsumer`、`getWaitingConsumerCount` |
| `BlockingDeque`、`LinkedBlockingDeque`、`ConcurrentLinkedDeque` | `Deque` のメンバー。ブロッキングデックにはブロッキングキューのメンバーと、両端それぞれの `putFirst`、`putLast`、`takeFirst`、`takeLast`、制限時間を指定する `offerFirst`、`offerLast`、`pollFirst`、`pollLast` もあります |
| `ConcurrentMap`、`ConcurrentHashMap` | `Map` のメンバー。キーと値を指定する `remove`、古い値を指定する `replace`、`mappingCount`、`contains`、`keys`、`elements`、値を指定する `keySet`、`ConcurrentHashMap.newKeySet`。引数なし、容量、マップを取るコンストラクタ。キーと値に `null` は使えません |
| `CopyOnWriteArrayList`、`CopyOnWriteArraySet` | `List` のメンバー、または `Set` のメンバー。`addIfAbsent`、`addAllAbsent`。イテレータは作られた時点のリストをたどり、リストを変更しません |
| `Executor`、`ExecutorService`、`Executors` | `execute`、`Callable` または `Runnable` を取る `submit`、`invokeAll`、制限時間の指定がある、またはない `invokeAny`、`shutdown`、`shutdownNow`、`isShutdown`、`isTerminated`、`awaitTermination`、`close`。`newFixedThreadPool`、`newSingleThreadExecutor`、`newCachedThreadPool`、`newScheduledThreadPool`、`newSingleThreadScheduledExecutor`（いずれも `ThreadFactory` の指定がある、またはない）、`newVirtualThreadPerTaskExecutor`、`newThreadPerTaskExecutor`、`unconfigurableExecutorService`、`unconfigurableScheduledExecutorService`、`defaultThreadFactory`、`callable` |
| `ThreadPoolExecutor`、`RejectedExecutionHandler` | コアサイズ、最大サイズ、時間、キューを取り、`ThreadFactory`、ハンドラー、またはその両方の指定がある、またはないコンストラクタ。`AbortPolicy`、`CallerRunsPolicy`、`DiscardPolicy`、`DiscardOldestPolicy`。`getPoolSize`、`getCorePoolSize`、`getMaximumPoolSize`、`getLargestPoolSize`、`getActiveCount`、`getTaskCount`、`getCompletedTaskCount`、`getKeepAliveTime`、`getQueue`、`getThreadFactory`、`getRejectedExecutionHandler`、`setRejectedExecutionHandler`、`setCorePoolSize`、`setMaximumPoolSize`、`setKeepAliveTime`、`setThreadFactory`、`allowCoreThreadTimeOut`、`allowsCoreThreadTimeOut`、`prestartCoreThread`、`prestartAllCoreThreads`、`remove`、`purge`、`isTerminating`、`toString`。オーバーライドできる `beforeExecute`、`afterExecute`、`terminated` |
| `ScheduledExecutorService`、`ScheduledThreadPoolExecutor`、`ScheduledFuture`、`Delayed` | `Runnable` または `Callable` を取る `schedule`、`scheduleAtFixedRate`、`scheduleWithFixedDelay`。コアサイズを取り、`ThreadFactory` の指定がある、またはないコンストラクタ。`setRemoveOnCancelPolicy`、`setExecuteExistingDelayedTasksAfterShutdownPolicy`、`setContinueExistingPeriodicTasksAfterShutdownPolicy` と、それぞれの値を読むメンバー。`getDelay`、`compareTo` |
| `ForkJoinPool`、`ForkJoinTask`、`RecursiveTask`、`RecursiveAction`、`CountedCompleter` | `new ForkJoinPool()`、並列度を取るコンストラクタ、`ForkJoinPool.commonPool`。`invoke`、`execute`、`submit`、`getParallelism`、`getCommonPoolParallelism`、`getPoolSize` と `ExecutorService` のメンバー。`fork`、`join`、`invoke`、`invokeAll`、制限時間の指定がある、またはない `get`、`cancel`、`isDone`、`isCancelled`、`isCompletedNormally`、`isCompletedAbnormally`、`getException`、`complete`、`completeExceptionally`、`quietlyComplete`、`quietlyJoin`、`quietlyInvoke`、`getRawResult`、`ForkJoinTask.adapt`、`inForkJoinPool`、`getPool`。`compute`。`CountedCompleter` には `tryComplete`、`propagateCompletion`、`onCompletion`、`onExceptionalCompletion`、`getCompleter`、`getRoot`、`getPendingCount`、`setPendingCount`、`addToPendingCount`、`compareAndSetPendingCount`、`decrementPendingCountUnlessZero`、`firstComplete`、`nextComplete`、`quietlyCompleteRoot`、`helpComplete` もあります |
| `Timer`、`TimerTask` | 引数なし、名前、デーモンかどうか、その両方を取るコンストラクタ。遅延を取り、周期の指定がある、またはない `schedule`、`scheduleAtFixedRate`、`cancel`、`purge`。`run`、`cancel`、`scheduledExecutionTime` |
| `Callable`、`Future`、`FutureTask`、`ThreadFactory` | `call`。制限時間の指定がある、またはない `get`、`isDone`、`cancel`、`isCancelled`、`state`、`resultNow`、`exceptionNow`。`run`、`toString`。`newThread` |
| `CompletableFuture`、`CompletionStage` | エグゼキューターの指定がある、またはない `supplyAsync` と `runAsync`、`completedFuture`、`failedFuture`、`completedStage`、`failedStage`、`allOf`、`anyOf`。`thenApply`、`thenAccept`、`thenRun`、`thenCompose`、`handle`、`whenComplete`、`thenCombine`、`thenAcceptBoth`、`runAfterBoth`、`applyToEither`、`acceptEither`、`runAfterEither`、`exceptionally`、`exceptionallyCompose`（いずれも、エグゼキューターの指定がある、またはない `Async` 版があります）。`complete`、`completeExceptionally`、`completeAsync`、`obtrudeValue`、`obtrudeException`、`cancel`、`orTimeout`、`completeOnTimeout`、`delayedExecutor`、`copy`、`minimalCompletionStage`、`newIncompleteFuture`、`defaultExecutor`、`join`、制限時間の指定がある、またはない `get`、`getNow`、`isDone`、`isCancelled`、`isCompletedExceptionally`、`getNumberOfDependents`、`state`、`resultNow`、`exceptionNow`、`toString` |
| `java.util.function` | `Function`、`BiFunction`、`UnaryOperator`、`BinaryOperator`、`Supplier`、`Consumer`、`BiConsumer`、`Predicate`、`BiPredicate`、`BooleanSupplier`。`Int`、`Long`、`Double` のそれぞれに `IntFunction`、`IntPredicate`、`IntUnaryOperator`、`IntBinaryOperator`、`IntSupplier`、`IntConsumer`、`ToIntFunction`、`ToIntBiFunction`、`ObjIntConsumer`。3 つの型の間の `IntToLongFunction`、`IntToDoubleFunction`、`LongToIntFunction`、`LongToDoubleFunction`、`DoubleToIntFunction`、`DoubleToLongFunction`。各インターフェースの抽象メソッドと `andThen`、`compose`、`and`、`or`、`negate`、`identity`、`not`、`isEqual` |
| `Duration` | `ZERO`。`ofDays`、`ofHours`、`ofMinutes`、ナノ秒の指定がある、またはない `ofSeconds`、`ofMillis`、`ofNanos`、`parse`。`getSeconds`、`getNano`、`isZero`、`isNegative`、`isPositive`。`Duration` を取る `plus` と `minus`、`plusDays` から `plusNanos`、`minusDays` から `minusNanos`、`multipliedBy`、`negated`、`abs`、`withSeconds`、`withNanos`。`toDays`、`toHours`、`toMinutes`、`toSeconds`、`toMillis`、`toNanos`、`toDaysPart` から `toNanosPart`。`compareTo`、`equals`、`hashCode`、`toString`。`DateTimeException`、`DateTimeParseException` |
| `URI`、`URISyntaxException` | `new URI(String)`、`URI.create`。`getScheme`、`getSchemeSpecificPart`、`getAuthority`、`getUserInfo`、`getHost`、`getPort`、`getPath`、`getQuery`、`getFragment` と、それぞれの `getRaw..`。`isAbsolute`、`isOpaque`、`URI` または文字列を取る `resolve`、`equals`、`hashCode`、`compareTo`、`toString`。`getInput`、`getReason`、`getIndex` |
| `HttpClient` | `newHttpClient`。`newBuilder` と、そのビルダーの `version`、`followRedirects`、`executor`、`connectTimeout`、`build`。`send`、`sendAsync`、`followRedirects`、`version`、`executor`、`connectTimeout`、`close`、`shutdown`、`shutdownNow`、`isTerminated`。`HttpClient.Version`、`HttpClient.Redirect` |
| `HttpRequest` | `URI` の指定がある、またはない `newBuilder` と、そのビルダーの `uri`、`header`、`headers`、`setHeader`、`GET`、`POST`、`PUT`、`DELETE`、`HEAD`、`method`、`version`、`expectContinue`、`timeout`、`copy`、`build`。`method`、`uri`、`headers`、`bodyPublisher`、`version`、`expectContinue`、`timeout`、`equals`、`hashCode`、`toString`。`Charset` の指定がある、またはない `BodyPublishers.ofString`、配列またはその範囲を取る `ofByteArray`、`ofInputStream`、`noBody`。`BodyPublisher.contentLength` |
| `HttpResponse` | `statusCode`、`headers`、`body`、`uri`、`request`、`previousResponse`、`version`、`toString`。`Charset` の指定がある、またはない `BodyHandlers.ofString`、`ofByteArray`、`ofInputStream`、`discarding`、`replacing`。同じ名前の `BodySubscribers` と `mapping`。`ResponseInfo`（`statusCode`、`headers`、`version`）を見てサブスクライバーを選ぶ、プログラム独自の `BodyHandler` |
| `HttpHeaders` | `firstValue`、`firstValueAsLong`、`allValues`、`map`、`HttpHeaders.of`、`equals`、`hashCode`、`toString` |
| `HttpTimeoutException`、`HttpConnectTimeoutException` | メッセージを取るコンストラクタ。時間制限を過ぎたときに `send` が投げ、`sendAsync` の future が失敗の原因として持つ例外です |
| `HttpServer`、`HttpContext`、`HttpHandler` | 引数なし、またはアドレスとバックログを取る `HttpServer.create`、`bind`、ハンドラーの指定がある、またはない `createContext`、パスまたはコンテキストを取る `removeContext`、`setExecutor`、`getExecutor`、`start`、`stop`、`getAddress`。`getPath`、`getHandler`、`setHandler`、`getServer`、`getAttributes`。`handle` |
| `HttpExchange` | `getRequestMethod`、`getRequestURI`、`getProtocol`、`getRequestHeaders`、`getRequestBody`、`getResponseHeaders`、`sendResponseHeaders`、`getResponseBody`、`getResponseCode`、`close`、`getHttpContext`、`getRemoteAddress`、`getLocalAddress`、`getAttribute`、`setAttribute`、`setStreams` |
| `Headers` | `Map` のメンバー。`getFirst`、`add`、`set`。引数なし、またはマップを取る `new Headers()`、名前と値を交互に並べるかマップを取る `Headers.of` |
| `InetSocketAddress`、`InetAddress`、`BindException` | ポート、ホストとポート、`InetAddress` とポートを取るコンストラクタ、`createUnresolved`。`getPort`、`getHostString`、`getHostName`、`getAddress`、`isUnresolved`、`equals`、`hashCode`、`toString`。`getHostAddress`、`getHostName`、`isLoopbackAddress`、`isAnyLocalAddress`、`InetAddress.getLoopbackAddress` |

`String.format`、`formatted`、`printf` が受け付ける変換は `%d`、`%s`、`%S`、`%f`、`%e`、`%E`、
`%x`、`%X`、`%o`、`%c`、`%b`、`%h`、`%H`、`%n`、`%%` です。フラグ `-`、`0`、`+`、空白、`,`、`(`、`#` と、
幅、精度、引数の番号（`%2$s`）を指定できます。

`java.util.regex.Pattern` と `Matcher` は、`Pattern` のドキュメントが記す構文を受け付けます。
和と積のある文字クラス、グループ（捕捉、名前付き、アトミック）、先読みと後読み、後方参照、
貪欲・最短・所有の量指定子、インラインフラグ、`\\p{..}` のプロパティ、`\\X` と `\\b{g}` です。
`String.matches`、`replaceAll`、`replaceFirst`、`split` も同じ構文を取ります。不正なパターンは
JDK と同じメッセージの `PatternSyntaxException` になります。`Matcher` はリージョンの中を
検索でき（`region`、`useAnchoringBounds`、`useTransparentBounds`）、検索が入力の終わりを
読んだかどうかを答え（`hitEnd`、`requireEnd`）、マッチごとに置換結果を組み立て
（`appendReplacement`、`appendTail`、関数を取る `replaceAll`）、マッチを保存します
（`toMatchResult`）。[言語との差異](deviations.md)も参照してください。

`java.io` のストリームは、配列、文字列、標準ストリーム、ほかのストリームの上に作ります。
ファイルはありません。`PrintStream`、`PrintWriter`、バイトと文字をつなぐ 2 つのクラスが
受け付ける文字セットは、`UTF-8`（デフォルト）、`ISO-8859-1`、`US-ASCII`、`UTF-16`、`UTF-16BE`、
`UTF-16LE` です。

```java
void main() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
        out.writeInt(2026);
        out.writeUTF("naïve");
    }
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    IO.println(bytes.size() + " bytes: " + in.readInt() + " " + in.readUTF());
    BufferedReader lines = new BufferedReader(new StringReader("first\nsecond"));
    for (String line = lines.readLine(); line != null; line = lines.readLine()) {
        IO.println(line.toUpperCase());
    }
}
```

```
12 bytes: 2026 naïve
FIRST
SECOND
```

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

素の Preview 1 モジュールはポートを開けません。`create` は、`--component` を示す
`IOException` を投げます。コンパクトソースファイルでは、`import module jdk.httpserver;` で
サーバーをインポートします。

プログラムは、開始したサーバーが動いているあいだ終了しません。`main` が戻ったあとも、`stop`
が呼ばれるまでリクエストに応答します。`wasmtime serve` では、ホストが必要に応じてプログラムを
起動して `main` を最後まで実行し、`create` に渡したアドレスは使われません。
`sendResponseHeaders(status, length)` の `length` は、ボディの長さです。`-1` はボディが
ないことを、`0` は長さを事前に知らせないボディを表します。長さを知らせないボディは、
トランスポートが対応していれば（`wasmtime serve`）、書き込むたびにチャンクとして送られます。
それ以外のトランスポートでは、全体がまとめて送られます。

次のプログラムは、コンテキストを 1 つ持つサーバーを開始し、自分でそのサーバーにリクエストを
1 回送ります。サーバーとクライアントが同じプロセスで動くため、実行できるのはスレッドのある
ターゲット（インタプリタと JVM）です。

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
