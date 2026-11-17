# 言語との差異

サブセットであることは設計上の選択です。サブセットが受け付けるプログラムの動作が言語仕様と
異なる箇所はここに挙げたとおりで、`.wasm` の[スレッド](#threads)を除けば、どのバックエンドでも
同じです。

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

**オーバーロードされたメソッドの引数になった呼び出しは、自身の引数だけで型が決まります。**
同じ数の引数を取るオーバーロードが複数あるメソッドの場合、その呼び出しの引数のラムダの引数型を
外側のメソッドしか与えられないと、エラーになります。`show(Comparator<String>)` と
`show(Runnable)` を宣言したプログラムでは、`show(Comparator.comparing(s -> s.length()))` が
`s.length()` で拒否されます。そこでの `s` は `Object` だからです。

**配列への格納は検査しません。** 配列自身の型より上の配列型を通して格納した値
（`Object[] a = new String[1]; a[0] = 1;`）は、言語仕様では `ArrayStoreException` になる場合でも
格納されます。

**`long`、`short`、参照型の配列のクラスは、拡大変換で記録したクラスです。** 値をこれらの配列型へ
キャストまたは判定（`instanceof`）するプログラムと、これらの配列でありうる値のクラス名
（`getClass()`）を求めるプログラムでは、配列の型を拡大したとき（`Object` や自身より上の配列型への
代入、型パラメーターの位置への受け渡し）にクラスを記録します。それ以外のプログラムでは、
`Object` として読んだこれらの配列の `toString()` は、`long` と参照型の配列を `Object[]`、
`short` の配列を `char[]` と名付けます。

```java
void main() {
    Object names = new String[] {"a"};
    String text = names.toString();
    IO.println(text.substring(0, text.indexOf('@')));
}
```

```
[Ljava.lang.Object;
```

JDK は `[Ljava.lang.String;` と出力します。

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
1 で終了します。JDK がフレームを書く位置には 1 行だけ続きます。例外が発生した文のファイルと
行、その文が書かれているメソッドです。インタプリタと JVM 出力はこの行を書きます。`.wasm`
出力は [`--report-locations`](../compiling/wasm.md#uncaught-condition-locations---report-locations)
を付けてコンパイルしたときに書きます。原因（cause）の行は
続きません。`printStackTrace()` は、例外、その例外が抑制した例外、原因を JDK と同じ形で
書きますが、フレームの行は書きません。

```console
$ rontolisp Uncaught.java
Exception in thread "main" java.lang.IllegalStateException: insufficient funds: 30
  at Uncaught.java:4 in Uncaught.check(int,int)
```

**コンパイラが報告するエラーの一部は報告されません。** そのようなプログラムは実行されます。
`throws` 節は読みますが検査しません。捕捉も宣言もされていないチェック例外と、`try` が投げ
得ないチェック例外の `catch` は報告しません。unchecked の警告は表示しません。
`protected` のメンバーには、そのクラスのサブクラスならどこからでも
アクセスできます。言語仕様では、インスタンスメンバーにはサブクラス自身の型の参照を通してだけ、
コンストラクタには `super(..)` を通してだけアクセスできます。

**レコードの `hashCode`** は、コンポーネントを宣言順に `31 * h + hash` で畳み込んだ値です。
言語仕様はこの値を定めていません。

**`hashCode` も `toString` も宣言していないオブジェクトの `hashCode` と `toString`** は、
rontolisp が割り当てる識別番号と、`Name@` にその番号の 16 進表記を続けた文字列です。
このようなオブジェクト（列挙型の定数を含みます）をキーにした `HashMap` は、JDK と異なる順序で
キーをたどります。JDK でも、この順序は実行のたびに変わります。

**`HashMap` の順序が JDK と異なる場合が 2 つあります。** バケットが 64 個以上の表で、ハッシュ
コードが 1 つのバケットに集まるキーが 8 個を超えると、それらのキーは追加した順にたどられます。
桁数の少ない `Double` のキー（`0.0`、`0.25`、`0.5` など。ハッシュコードが上位ビットでしか
違いません）は、64 個からこの状態になります。`hashCode` が取る値の少ないクラスのキーも同様です。
文字列と整数のキーはこの状態になりません。
`Set.of` と `Map.of` の順序は 1 つに決まっています。JDK は起動のたびに別の順序を選びます。

**`Math` の関数は `StrictMath` の関数です。** そのため、結果はどのバックエンドとプロセッサでも
同じです。JDK の `Math.sin`、`Math.exp` などは、最後の桁がこれと異なることがあります。

**NaN はペイロードを持ちません。** NaN はすべて 1 つの値です。`floatToRawIntBits` と
`doubleToRawLongBits` は `floatToIntBits` と `doubleToLongBits` と同じビットを返し、
`intBitsToFloat` と `longBitsToDouble` はどの NaN のビットからもその NaN を返します。

**`String.split` が読む正規表現は一部だけです。** 演算子を含まない文字列か、1 つの文字クラス
（`+` を続けられます）を受け付けます。それ以外の正規表現は実行時のエラーになります。
`String.format` の変換は[概要](index.md)に挙げたものだけで、それ以外の変換は名前を示す
エラーになります。

**古い 2 つのクラスは新しいクラスと同じものです。** `StringBuffer` は `StringBuilder` であり、
`getClass().getName()` も `StringBuilder` の名前を返します。`Vector` は `instanceof` で
`ArrayList` と判定されます。

**標準ストリームは文字のストリームです。** `System.in` は、ホストの標準入力の文字を、その
UTF-8 のバイト列として読みます。そのため、テキストでない入力は元のバイト列のままでは
読めません。`System.out` と `System.err` に書いたバイトは UTF-8 のバイトとして扱い、文字は
その最後のバイトが書かれたときに出力します。`System.in` から複数のバイトを読むと、最初の
1 バイトを待ち、その時点で読めるバイトまでを返します。`.wasm` は読めるバイトの有無をホストに
問い合わせられないため、行末まで読みます。`System.out` と `System.err` は差し替えられず
（`System.setOut`）、`close()` の後も出力できます。

**文字セットは 3 つです。** デフォルトの `UTF-8` と、`ISO-8859-1`、`US-ASCII` です。それ以外の
名前は `UnsupportedEncodingException` または `UnsupportedCharsetException` になります。UTF-8
として不正なバイト列は U+FFFD にデコードします。U+FFFD の個数は JDK 25 と同じです。

## スレッド

**`.wasm` は、プログラムのスレッドを 1 本のスタックで実行します。** 開始されたスレッドは、
実行中のスレッドが先へ進めなくなるまで動きません。先へ進めなくなるのは、`wait` または `join` で
待機したとき、スリープしたとき、`yield` したとき、`main` のスレッドが終了したときです。
そのとき、開始済みのスレッドが、待機したスレッドのスタックの上で、開始された順に 1 つずつ
最後まで実行されます。言語仕様はスレッドに実行の順番を保証していないため、この実行順序は
どれも仕様の範囲内ですが、JDK ではほとんど起こりません。違いが現れるのは次の 3 種類の
プログラムです。

- 上のようにして中断されたスレッドは、その上で実行されたスレッドがすべて終了するまで再開
  しません。中断中のスレッドにしか終わらせられない待機は、その待機が起きた時点で拒否されます。
  標準エラーにその旨のメッセージを書き出し、ステータス 1 で終了します。該当するのは、2 つの
  スレッドが `wait` と `notify` で交互に動くプログラムや、生産者が消費者を待つ有界バッファ
  です。
- 実行中のスレッドが途中で中断されることはありません。フラグや割り込みで停止を指示される
  まで動き続けるスレッドは、指示を出す側のスレッドが自分の下で中断されていると、終了
  しません。
- `sleep` は指定した時間以上スリープします。あるスレッドの下でスリープしているスレッドは、
  上のスレッドが終了してから再開するため、スリープ時間の異なる 2 つのスレッドの出力順が
  JDK と逆になることがあります。

デーモンスレッドは、ほかのスレッドがすべて終了した時点でまだ動いていなければ、実行
されません。

**モニターも `volatile` も使わずに共有したデータの見え方は、ホストに従います。**
インタープリターと JVM では、2 つのスレッドがどちらも使わずに読み書きするフィールドから、
古い値が読まれることがあります。これは言語仕様が許す動作です。`volatile`、`synchronized`、
`Thread.start`、`join` による順序付けは言語仕様のとおりです。

**JDK が同期しているクラスは、ロックを取りません。** `StringBuffer`、`Vector`、`Hashtable` は
同期しないクラスとして実装されているため、スレッド間で共有するときは `synchronized` で保護
します。`AtomicInteger` と `AtomicLong` は `Number` ではありません。

**スレッドの番号は rontolisp 独自のものです。** `threadId()` と `toString()` に含まれる番号は、
プログラムのスレッドを 1 から数えた値です。クラスリテラルは `synchronized` 文のロックとして
だけ読めます。すべてのクラスの static 初期化を 1 つのロックで保護しているため、別のクラスを
初期化するスレッドを待つ static 初期化子は終了しません。

## 拒否される構文

どれも、構文の名前と位置を示すエラーになります。

- アノテーション型、`assert`。
- `synchronized` 文のロック以外のクラスリテラル。
- `long`、`float`、`double`、`boolean` に対する `switch`。修飾した `super` を通すメソッド参照
  （`Interface.super::m`）。型引数を書くコンストラクタ参照（`Box::<T>new`）。
- スーパークラスのフィールドを隠すフィールド。
- 複数の型へのラムダとメソッド参照のキャスト（`(Runnable & Marker) () -> ..`）。
- `String`、`StringBuilder`、ボックスを継承するクラス。
- 4 つのアトミッククラスを除く `java.util.concurrent`。ロック、エグゼキューター、並行
  コレクション、`CompletableFuture` などです。`Thread.getState`、スレッドグループ、未捕捉例外
  ハンドラーも拒否されます。
- [概要](index.md)の表にないライブラリのクラスとメンバー。ストリーム（`java.util.stream`）、
  `LinkedList`、`LinkedHashMap`、`TreeMap`、`Scanner`、ファイル（`File`、`FileReader`、
  `java.nio.file`）、`java.time`、`BigInteger`、`BigDecimal`、正規表現、リフレクションなどです。
