# Java 言語のサブセット（実験的）

**実験的。** rontolisp は Java 言語のサブセットを読み、Common Lisp と同じコアフォームに
変換します。そのため `.java` のプログラムはどのバックエンドでも動きます。サブセットであることは
設計上の選択で、ここにあるものは互換性の保証ではありません。サブセットにない構文は、
プログラムを実行する前に、構文の名前と位置を示して拒否します。フロントエンドとライブラリは
公開されている仕様から rontolisp のために書いたものです。JDK のコードは読み込まず、同梱せず、
コンパイルしたプログラムにも含めません。

`.java` のファイルはこのサブセットとして読まれます。それ以外のファイルと `-e` では
`--source-language java` で指定します。プログラムは 1 つのファイルです。

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

## 読める構文

| | |
|---|---|
| 型 | `int`、`long`、`double`、`boolean`、`char`、`String`、クラス、インターフェース、列挙型、レコード、配列。ローカル変数の `var` |
| 宣言 | `extends` と `implements` を持つクラス、抽象クラスと抽象メソッド。フィールド、コンストラクタ、メソッド、本体付きの定数を持つ列挙型。コンパクトコンストラクタと標準コンストラクタを持つレコード。抽象メソッド、`default` メソッド、`static` メソッド、`private` メソッド、定数を持つインターフェース。`sealed`、`permits`、`non-sealed`。static なネストクラス、内部クラス、ローカルクラス、匿名クラス。フィールド、static フィールド、static 初期化ブロックとインスタンス初期化ブロック。`this(..)` と `super(..)`、およびその前の文を持つコンストラクタ。メソッドのオーバーロードと可変長引数。`package` と `import` |
| 文 | ローカル変数（`final`、無名変数 `_`）、`if`、`while`、`do`、`for`、配列またはリストに対する拡張 `for`、コロン形式または矢印形式の `case`・パターン・ガードを持つ `switch`、ラベル付きの文、ラベルの有無を問わない `break` と `continue`、`yield`、`return`、`throw`、`catch` と `finally` を持つ `try` |
| 式 | プリミティブ型のすべての演算子とその変換、文字列連結、条件演算子、代入と複合代入、キャスト、型・型パターン・レコードパターンに対する `instanceof`、`switch` 式、`new`、`outer.new Inner()`、`Outer.this`、長さを 1 つ以上指定する配列の生成と初期化子、テキストブロック、メソッド呼び出し、`super.m(..)`、抽象メソッドを 1 つ持つプログラム内のインターフェースに対するラムダ |

アノテーションは読み飛ばします。`int` と `long` は 2 の補数として桁あふれします。`double` は、
読み戻すと同じ値になる最短の文字列で表示されます。

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

## ライブラリ

各クラスにあるメンバーは次の表のものだけです。表にないメンバーは名前を示して拒否します。

| クラス | メンバー |
|---|---|
| `Object` | `toString`、`equals`、`hashCode`、`getClass().getName()`、`getClass().getSimpleName()` |
| 列挙型 | `values()`、`valueOf(String)`、`name`、`ordinal`、`compareTo`、`toString` |
| レコード | アクセサ、`equals`、`hashCode`、`toString` |
| 配列 | `length`、`clone()` |
| `String` | `length`、`charAt`、`substring`、`indexOf(String)`、`equals`、`isEmpty`、`toUpperCase`、`toLowerCase`、`hashCode`、`String.valueOf` |
| `StringBuilder` | `new StringBuilder()`、`new StringBuilder(String)`、`append`、`toString`、`length`、`charAt` |
| `Integer`、`Long`、`Double`、`Boolean`、`Character` | ボクシングとアンボクシング。`Integer.MAX_VALUE`、`MIN_VALUE`、`parseInt`、`toString(int)`、`valueOf(int)`、`intValue`。`Long.MAX_VALUE`、`MIN_VALUE`、`Long.hashCode(long)`。`Double.compare`。`Boolean.hashCode(boolean)` |
| `Math` | `sqrt`、`floor`、`pow`、`abs`、`max`、`min`、`PI`、`E` |
| `System.out`、`System.err`、`IO` | `println`、`print` |
| `ArrayList`、`List` | `new ArrayList<>()`、`add`、`get`、`set`、`size`、`isEmpty` |
| `HashMap`、`Map` | `new HashMap<>()`、`put`、`get`、`getOrDefault`、`containsKey`、`size`、`isEmpty`。キーは `String`、`Integer`、`Long`、`Character`、`Boolean` |
| 例外 | `Throwable`、`Exception`、`Error`、`RuntimeException`、`IllegalArgumentException`、`IllegalStateException`、`ArithmeticException`、`NullPointerException`、`ClassCastException`、`IndexOutOfBoundsException`、`ArrayIndexOutOfBoundsException`、`UnsupportedOperationException`、`NumberFormatException`。引数のないコンストラクタとメッセージを取るコンストラクタ、`getMessage`、`toString` |
| `Runnable` | `run` |

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
