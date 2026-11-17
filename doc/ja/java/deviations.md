# 言語との差異

サブセットであることは設計上の選択です。サブセットが受け付けるプログラムの動作が言語仕様と
異なる箇所はここに挙げたとおりで、どのバックエンドでも同じです。

## 動作が異なる点

**文字列はコードポイントの列です。** `length`、`charAt`、`substring` は Unicode の
コードポイントを数えます。言語仕様は UTF-16 のコード単位を数えるので、基本多言語面の外の
文字を含む文字列では結果が変わります。`hashCode` は仕様どおりの値です。`toCharArray` は
UTF-16 のコード単位を返し、`new String(char[])` はそれを元の文字列に読み戻します。

```java
void main() {
    String s = "a𝄞b";
    IO.println(s.length() + " " + s.indexOf("b") + " " + s.hashCode());
}
```

```
3 2 57834039
```

JDK は `4 3 57834039` を表示します。

**2 つのボックスの `==` は値を比較します。** ボックスは値そのものなので、同じ値のボックスは
同じオブジェクトです。言語仕様は、-128 から 127 までの値についてこれを保証し、それ以外の値に
ついても許しています。

```java
void main() {
    Integer a = 1000, b = 1000;
    Long big = 1000L;
    Object boxed = big;
    IO.println((a == b) + " " + boxed.equals(a) + " " + (boxed instanceof Long));
}
```

```
true false true
```

JDK は `false false true` を表示します。

**型引数は実行時に検査しません。** 言語仕様では、型引数を通して読んだ値は、使う箇所で検査
されます。rontolisp は型を信頼します。型引数と異なるクラスの値が入るのは、raw 型を通した
場合か、コンパイラが unchecked と警告するキャストを通した場合だけです。その値は、言語仕様では
`ClassCastException` になる箇所でも、そのまま使われます。型変数へのキャストはその境界を、
型引数付きの型へのキャストはそのクラスを検査します。

**配列への格納は検査しません。** 参照型の配列は要素の型を記録していません。そのため、配列自身の型
より上の配列型を通して格納した値（`Object[] a = new String[1]; a[0] = 1;`）は、言語仕様では
`ArrayStoreException` になる場合でも格納されます。

**クラスが最初の使用時に初期化されるのは、プログラムからその違いを観測できる場合だけです。**
言語仕様では、クラスは最初に使われたときに初期化されます。rontolisp も、static 初期化が
次のいずれかを行うクラスは、最初に使われたときに初期化します。出力する、例外を投げる、
ほかのクラスの static フィールドに書き込む、ほかのクラスから「変更できない値を持つ `final`
フィールド」以外のものを読む、のいずれかです。それ以外のクラスは `main` の前に初期化します。
そのクラスの初期化は、最初の使用時に行った場合と区別できないからです。

```java
class Config {
    static int level = 1;
}

class Table {
    static final int[] SQUARES = new int[4];

    static {
        for (int i = 0; i < SQUARES.length; i++) {
            SQUARES[i] = i * i;
        }
    }
}

class Report {
    static final int LEVEL = Config.level;

    static {
        IO.println("Report is initialized");
    }
}

void main() {
    IO.println("main starts");
    Config.level = 3;
    IO.println(Table.SQUARES[3] + " " + Report.LEVEL);
}
```

```
main starts
Report is initialized
9 3
```

言語仕様と異なる点が 2 つあります。`main` の前に行われる初期化の中で実行時エラー（表を埋める
途中での配列の範囲外の添字など）が起きると、そのエラーは `main` が始まる前に発生します。
static 初期化子が投げた例外は、`ExceptionInInitializerError` に包まれず、そのままプログラムに
届きます。

**実行時の例外のメッセージのうち 2 つが異なります。** 実行時のエラーは言語仕様が定める例外で、
`catch` はその例外のクラスとして捕捉します。`null` を通したメンバー・要素・長さの読み出し、
`null` のボックスのアンボクシング、`null` に対する `switch` は `NullPointerException`、
0 による整数の除算は `ArithmeticException`、配列・文字列・`StringBuilder` の範囲外の添字は
`ArrayIndexOutOfBoundsException` または `StringIndexOutOfBoundsException`、負の長さの配列は
`NegativeArraySizeException`、引数と変換が合わない `String.format` は
`IllegalFormatException` です。メッセージは JDK のものと同じですが、2 つだけ異なります。
実行時に起きる `NullPointerException` はメッセージを持ちません。JDK のメッセージは `null`
だった式を名前で説明し、その名前はクラスのコンパイル方法によって変わるためです。
`ClassCastException` のメッセージは 2 つのクラスを示し、JDK が加えるモジュールとクラス
ローダーの説明を含みません。スタックオーバーフローは、プログラムが捕捉できる `StackOverflowError` になりません。

**捕捉されなかった例外は、スタックトレースなしで報告されます。** 標準エラー出力に、JDK が
最初に書く行、つまり `Exception in thread "main"` と例外の `toString()` を書き、終了ステータス
1 で終了します。フレームと原因（cause）の行は続きません。`printStackTrace()` は、例外、
その例外が抑制した例外、原因を JDK と同じ形で書きますが、フレームの行は書きません。

```console
$ rontolisp Uncaught.java
Exception in thread "main" java.lang.IllegalStateException: insufficient funds: 30
```

**コンパイラが報告するエラーの一部は報告されません。** そのようなプログラムは実行されます。
`throws` 節は読みますが検査しません。捕捉も宣言もされていないチェック例外と、`try` が投げ
得ないチェック例外の `catch` は報告しません。代入前のローカル変数の読み出しは、`if`、`while`、
`for` の分岐をたどって、どの経路でも代入されていない場合に報告します。`switch`、`try`、`do`
ループ、条件式の中にある代入は、行われたものとして扱います。到達できない文は、`return`、
`throw`、`break`、`continue`、終わらないループの直後にある場合に報告します。`return` の不足は、
メソッドの最後の文が必ず正常に完了する場合に報告します。コンパイラがこれら以外の場合に
見つけるエラーは報告しません。初期値なしで宣言して 2 回代入したローカル変数を、ラムダや
ローカルクラスから読んでも拒否しません。型パラメータの境界を満たさない型引数は報告せず、
unchecked の警告も表示しません。アクセス修飾子のうち検査するのは `private` です。
プログラムは 1 つのファイルなので、ほかの修飾子によってアクセスが制限されることはありません。

**レコードの `hashCode`** は、コンポーネントを宣言順に `31 * h + hash` で畳み込んだ値です。
言語仕様はこの値を定めていません。

**`hashCode` も `toString` も宣言していないオブジェクトの `hashCode` と `toString`** は、
rontolisp が割り当てる識別番号と、`Name@` にその番号の 16 進表記を続けた文字列です。
このようなオブジェクト（列挙型の定数を含みます）をキーにした `HashMap` は、JDK と異なる順序で
キーをたどります。JDK でも、この順序は実行のたびに変わります。

**`HashMap` の順序が JDK と異なる場合が 2 つあります。** バケットが 64 個以上の表で、ハッシュ
コードが 1 つのバケットに集まるキーが 8 個を超えると、それらのキーは追加した順にたどられます。
`Set.of` と `Map.of` の順序は 1 つに決まっています。JDK は起動のたびに別の順序を選びます。

**`Math` の関数は `StrictMath` の関数です。** そのため、結果はどのバックエンドとプロセッサでも
同じです。JDK の `Math.sin`、`Math.exp` などは、最後の桁がこれと異なることがあります。

**`String.split` が読む正規表現は一部だけです。** 演算子を含まない文字列か、1 つの文字クラス
（`+` を続けられます）を受け付けます。それ以外の正規表現は実行時のエラーになります。
`String.format` の変換は[概要](index.md)に挙げたものだけで、それ以外の変換は名前を示す
エラーになります。

**古い 2 つのクラスは新しいクラスと同じものです。** `StringBuffer` は `StringBuilder` であり、
`getClass().getName()` も `StringBuilder` の名前を返します。`Vector` は `instanceof` で
`ArrayList` と判定されます。

## 拒否される構文

どれも、構文の名前と位置を示すエラーになります。

- static import、アノテーション型、`assert`。
- クラスリテラル。
- `long`、`float`、`double`、`boolean` に対する `switch`。コロン形式の `case` が次の `case` に
  フォールスルーする `switch` 式。`Interface.super.m()`。内部クラスを、その外側のクラスの外で
  継承するクラス。`outer.new` または `<>` で作る匿名クラス。
- スーパークラスのフィールドを隠すフィールド。
- `Outer<A>.Inner` と書く型。型パラメータを持つクラスの内部クラスのメンバーを、そのクラスの
  外から読むこと。複数の型へのキャスト（`(A & B) x`）。`int[]`、`byte[]`、`float[]`、
  `double[]`、`boolean[]` 以外の配列型へのナローイングキャストと `instanceof`。
  型に型引数があるメソッド参照（`List<String>::size`）と、自身の型引数を書くメソッド参照
  （`this::<T>m`）。
- `String`、`StringBuilder`、ボックスを継承するクラス。
- スレッド、`synchronized`、`volatile`。
- [概要](index.md)の表にないライブラリのクラスとメンバー。ストリーム、`LinkedList`、
  `LinkedHashMap`、`TreeMap`、`Scanner`、`java.io`、`java.time`、`BigInteger`、
  `BigDecimal`、正規表現、リフレクションなどです。
