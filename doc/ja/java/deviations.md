# 言語との差異

サブセットであることは設計上の選択です。サブセットが受け付けるプログラムの動作が言語仕様と
異なる箇所はここに挙げたとおりで、`.wasm` の[スレッド](#threads)を除けば、どのバックエンドでも
同じです。

## 動作が異なる点

**文字列はコードポイントの列です。** `length`、`charAt`、`substring` は Unicode の
コードポイントを数えます。言語仕様は UTF-16 のコード単位を数えるので、基本多言語面の外の
文字を含む文字列では結果が変わります。`hashCode` は仕様どおりの値です。`toCharArray` は
UTF-16 のコード単位を返し、`new String(char[])` はそれを元の文字列に読み戻します。
`toUpperCase` と `toLowerCase` は 1 文字を 1 文字に変えます。`"ß".toUpperCase()` は `ß` です
（JDK は `SS` を返します）。相手のないサロゲートは、UTF-8 では 3 バイトになります（JDK は
`?` を書きます）。`Character.digit`、`getNumericValue`、`Integer.parseInt` は、ASCII の外の
数字（`'٣'`）をインタプリタと JVM では読み、`.wasm` では読みません。

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

static 初期化の中で、またはそこから呼ばれたコードの中で `throw` 文が投げた例外は、言語仕様の
とおりに初期化を終わらせます。その例外は `ExceptionInInitializerError` の原因になり、`Error`
はそのまま届き、以後そのクラスを使うたびに `NoClassDefFoundError` が投げられます。
static 初期化の中で起きた実行時エラーの扱いは、言語仕様と異なります。エラーはそのまま
プログラムに届き、クラスは初期化が進んだところまでの状態で使い続けられます。`main` の前に
行われる初期化の中で起きた場合は、`main` が始まる前に発生します。

```java
class Table {
    static final int[] CELLS = new int[2];

    static {
        IO.println("filling");
        CELLS[2] = 1;
    }
}

void main() {
    try {
        IO.println(Table.CELLS.length);
    } catch (Throwable thrown) {
        IO.println(thrown);
    }
    IO.println(Table.CELLS.length);
}
```

```
filling
java.lang.ArrayIndexOutOfBoundsException: Index 2 out of bounds for length 2
2
```

JDK は最初の読み出しで `java.lang.ExceptionInInitializerError` を表示し、2 回目の読み出しで
`NoClassDefFoundError` を投げてプログラムを終了します。

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

**捕捉されなかった例外は、スタックトレースなしで報告されます。** 標準エラー出力には、JDK が
書く内容からフレームの行を除いたものを書きます。`Exception in thread "main"` と例外の
`toString()`、続いてその例外が抑制した例外と原因です。そのあと終了ステータス 1 で終了します。
フレームの代わりに、原因の行のあとに 1 行だけ書きます。例外が発生した文のファイルと行、
その文が書かれているメソッドです。インタプリタと JVM 出力はこの行を書きます。`.wasm` 出力は
[`--report-locations`](../compiling/wasm.md#uncaught-condition-locations---report-locations)
を付けてコンパイルしたときに書きます。`printStackTrace()` は、この 1 行を除いた同じ行を
書きます。
例外はフレームを保持しません。`getStackTrace()` は、`setStackTrace` のあとも空の配列を
返します。

```java
public class Uncaught {
    static void check(int balance, int amount) {
        if (amount > balance) {
            throw new IllegalStateException("insufficient funds: " + (amount - balance));
        }
    }

    static void withdraw(int balance, int amount) {
        try {
            check(balance, amount);
        } catch (IllegalStateException e) {
            throw new RuntimeException("withdrawal refused", e);
        }
    }

    public static void main(String[] args) {
        withdraw(70, 100);
    }
}
```

```console
$ rontolisp Uncaught.java
Exception in thread "main" java.lang.RuntimeException: withdrawal refused
Caused by: java.lang.IllegalStateException: insufficient funds: 30
  at Uncaught.java:12 in Uncaught.withdraw(int,int)
```

**コンパイラが報告するエラーの一部は報告されません。** そのようなプログラムは実行されます。
`throws` 節は読みますが検査しません。捕捉も宣言もされていないチェック例外と、`try` が投げ
得ないチェック例外の `catch` は報告しません。コンパイラが表示する警告のうち、表示するのは
呼び出し、変換、キャスト、可変長引数の [unchecked の警告](index.md#unchecked-warnings)だけです。
`protected` のメンバーには、そのクラスのサブクラスならどこからでも
アクセスできます。言語仕様では、インスタンスメンバーにはサブクラス自身の型の参照を通してだけ、
コンストラクタには `super(..)` を通してだけアクセスできます。

**レコードの `hashCode`** は、コンポーネントを宣言順に `31 * h + hash` で畳み込んだ値です。
言語仕様はこの値を定めていません。

**`hashCode` も `toString` も宣言していないオブジェクトの `hashCode` と `toString`** は、
rontolisp が割り当てる識別番号と、`Name@` にその番号の 16 進表記を続けた文字列です。
ネストしたクラスの名前は、この文字列ではドット区切り（`Outer.Inner@1f`）になります。
`getClass().getName()` と JDK は `Outer$Inner` と書きます。
このようなオブジェクト（列挙型の定数を含みます）をキーにした `HashMap` は、JDK と異なる順序で
キーをたどります。JDK でも、この順序は実行のたびに変わります。

**`HashMap` の順序が JDK と異なる場合が 2 つあります。** バケットが 64 個以上の表で、ハッシュ
コードが 1 つのバケットに集まるキーが 8 個を超えると、それらのキーは追加した順にたどられます。
桁数の少ない `Float` のキー（`0.0`、`0.5`、`1.0` など。ハッシュコードが上位ビットでしか
違いません）は 25 個から、桁数の少ない `Double` のキーは 64 個からこの状態になります。`hashCode` が取る値の少ないクラスのキーも同様です。
文字列と整数のキーはこの状態になりません。
`Set.of` と `Map.of` の順序は 1 つに決まっています。JDK は起動のたびに別の順序を選びます。

**`Math` の関数は `StrictMath` の関数です。** そのため、結果はどのバックエンドとプロセッサでも
同じです。JDK の `Math.sin`、`Math.exp` などは、最後の桁がこれと異なることがあります。

**NaN はペイロードを持ちません。** NaN はすべて 1 つの値です。`floatToRawIntBits` と
`doubleToRawLongBits` は `floatToIntBits` と `doubleToLongBits` と同じビットを返し、
`intBitsToFloat` と `longBitsToDouble` はどの NaN のビットからもその NaN を返します。

**正規表現は UTF-16 の単位ではなく文字を数えます。** `java.util.regex` と、パターンを取る
`String` のメンバーは、`Pattern` のドキュメントを基に本プロジェクトで書いたエンジンの上で
動きます。マッチの `start` と `end` は入力の文字数で数えます（ここでの `length` と `charAt` と
同じです）。JDK の `find` がサロゲートペアの間に入る場合は、マッチが異なります。`CANON_EQ` の
下の文字クラスとプロパティ、および `\N{name}` は、パターンのコンパイル時にエラーになります。
`Pattern` にあるのは `compile`、`matches`、`quote`、`matcher`、`split`、`pattern`、`flags`、
`asPredicate`、`asMatchPredicate` とフラグの定数です。`Matcher` にあるのは `find`、`lookingAt`、
`matches`、`group`、`start`、`end`、`groupCount`、`hasMatch`、`reset`、`region`、`regionStart`、
`regionEnd`、アンカー境界と透過境界の設定、`hitEnd`、`requireEnd`、文字列または関数を取る
`replaceAll` と `replaceFirst`、`appendReplacement`、`appendTail`、`toMatchResult`、
`quoteReplacement` です。`results`、`usePattern`、`namedGroups`、ストリームはありません。
透過境界のリージョンで `lookingAt` または `matches` を呼んだあと、後読みがリージョンより前を
読んでいると、JDK の `start()` は 0 を返します。ここではリージョンの開始位置を返します。

**`String.format` の変換は[概要](index.md)に挙げたものだけです。** それ以外の変換は名前を
示すエラーになります。ロケールはルートと英語のものだけで、どれもロケールなしの
`String.format` と同じ書式になります。デフォルトのロケールは `Locale.US` です。

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

**文字セットは 6 つです。** デフォルトの `UTF-8` と、`ISO-8859-1`、`US-ASCII`、`UTF-16`、
`UTF-16BE`、`UTF-16LE` です。それ以外の名前は `UnsupportedEncodingException` または
`UnsupportedCharsetException` になります。UTF-8 または UTF-16 として不正なバイト列は U+FFFD に
デコードします。U+FFFD の個数は JDK 25 と同じです。`UTF-16` の `PrintStream` の `charset()` は、
最初の出力のあと、同じ名前の別のオブジェクトを返します。

**クラスを表すオブジェクトはありません。** `getClass()` とクラスリテラル（`Name.class`）は、
互いを `==`、`!=`、`equals` で比較する箇所と、`getName()`、`getSimpleName()` の直前でだけ
読めます。それ以外の箇所ではエラーになります。

**ホストへの問い合わせには決まった値を返します。** `Runtime.availableProcessors()` は 1 です。
`System.getProperty` は `line.separator`、`file.separator`、`path.separator` と、エンコーディング
（`file.encoding`、`native.encoding`、`stdin.encoding`、`stdout.encoding`、`stderr.encoding`。
どれも `UTF-8`）に答えます。それ以外のキーは `null` です。`System.gc()` は何もしません。

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

**`.wasm` では、プールのスレッドはタスクがなくなると終了します。** `.wasm` のエグゼキューターの
スレッドは、次のタスクを待機できません。次のタスクを渡せるのが、そのスレッドの下で中断
されているスレッドだけだからです。あとからタスクを渡すと、同じ名前のスレッドが新しく
開始されるため、プールのスレッドの `ThreadLocal` は初期値に戻ります。シャットダウンしない
プールがあると、JDK ではプログラムが終了しませんが、`.wasm` では終了します。

**`.wasm` では、時刻を指定したタスクは、その時刻のために開始されたスレッドが実行します。**
`.wasm` のスレッドは、時刻が来るまで待機できません。そこで、スケジュール実行のプールと
`Timer` は、最も早いタスクの時刻より前には動かないスレッドを開始します。プログラムが
`get`、`await`、`join`、`sleep` で待機したとき、またはプログラムの終わりに達したときに、
その時刻まで待ってからタスクが実行されます。実行のたびに新しいスレッドが使われるため、
`ThreadLocal` は初期値に戻ります。新しいスレッドには、プールのスレッドの名前が順番に
付きます。デーモンでない `Timer` をキャンセルしないままにすると、JDK ではプログラムが
終了しませんが、`.wasm` ではタスクがなくなった時点で終了します。

**プールには JDK と異なる点があります。** `Executors.newSingleThreadExecutor()` と
`newSingleThreadScheduledExecutor()` はプールそのものを返します。JDK はプールをラッパーで
包んで返すため、JDK では戻り値が `ThreadPoolExecutor` ではありません。`ThreadPoolExecutor`
には、オーバーライドするための `beforeExecute`、`afterExecute`、`terminated` がなく、
セッターはハンドラーのものだけです。プログラムが `getQueue()` に直接入れたタスクは、次の
`execute` まで実行されません。スケジュール実行のプールはタスクを独自の表に保持するため、
`getQueue()` は空です。`ForkJoinPool` は、タスクを実行できるスレッドが空いていなければ
スレッドを開始します。そのため並列度は問い合わせに答えるだけの数値で、`new ForkJoinPool()`
では 2、共通プールでは 1 です。タスクが投げた例外は、そのまま再スローされます。JDK は、
別のスレッドがタスクを実行した場合、元の例外を原因に持つ同じクラスの例外を投げます。

**`ConcurrentHashMap` の走査順は JDK 25 と同じです。** ただし、チェーンが 64 本以上ある
テーブルで、1 本のチェーンに 9 個のキーが入った場合の順序は一致しません。

**スレッドの状態が `BLOCKED` になることはありません。** `getState` で分かるのは、スレッドが
待機中またはスリープ中であることです。モニターに入るのを待っているスレッドの状態は
`RUNNABLE` です。未捕捉例外ハンドラーが呼ばれるのはプログラムが開始したスレッドだけで、
`main` のスレッドでは呼ばれません。`AtomicInteger` と `AtomicLong` は `Number` では
ありません。

**スレッドの番号は rontolisp 独自のものです。** `threadId()` と `toString()` に含まれる番号は、
プログラムのスレッドを 1 から数えた値です。

## 拒否される構文

どれも、構文の名前と位置を示すエラーになります。

- アノテーション型、`assert`。
- 値として使うクラスリテラルと `getClass()`。どちらも、クラスとの比較と `getName()`、
  `getSimpleName()` の直前でだけ読めます。クラスリテラルは `synchronized` 文のロックにも
  なります。プリミティブ型と配列型のクラスリテラル。
- `long`、`float`、`double`、`boolean` に対する `switch`。修飾した `super` を通すメソッド参照
  （`Interface.super::m`）。
- スーパークラスのフィールドを隠すフィールド。
- `String`、`StringBuilder`、ボックスを継承するクラス。
- `java.util.concurrent` のうち、[概要](index.md)の表にないもの。スタンプ付きロック、
  `Phaser`、優先度順または遅延順のキュー、`ConcurrentSkipListMap`、`CountedCompleter`、
  表にないアトミッククラスです。`Date` を取る `Timer` の `schedule`、スレッドグループ、
  `Duration` を取る `sleep` と `join` も拒否されます。
- [概要](index.md)の表にないライブラリのクラスとメンバー。ストリーム（`java.util.stream`）、
  `LinkedList`、`LinkedHashMap`、`TreeMap`、`Scanner`、ファイル（`File`、`FileReader`、
  `java.nio.file`）、`java.time`、`BigInteger`、`BigDecimal`、リフレクションなどです。
