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
`protected` のメンバーには、そのクラスのサブクラスならどこからでも
アクセスできます。言語仕様では、インスタンスメンバーにはサブクラス自身の型の参照を通してだけ、
コンストラクタには `super(..)` を通してだけアクセスできます。コンパイラが表示する警告のうち、
表示するのは呼び出し、変換、キャスト、可変長引数の
[unchecked の警告](index.md#unchecked-warnings)と、到達しない `catch` 節の警告だけです。

**レコードの `hashCode`** は、コンポーネントを宣言順に `31 * h + hash` で畳み込んだ値です。
言語仕様はこの値を定めていません。

**`hashCode` も `toString` も宣言していないオブジェクトの `hashCode` と `toString`** は、
rontolisp が割り当てる識別番号と、`Name@` にその番号の 16 進表記を続けた文字列です。
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

**`.wasm` のスレッドは、交代しながら 1 つずつ実行されます。** `.wasm` にはスタックが 1 本しか
ないため、スレッドは待機するまで実行を続け、待機したときに、実行できる別のスレッドへ交代
します。待機するのは、`wait`、`join`、`sleep`、`yield` を呼んだときと、別のスレッドが保持
しているモニターに入ろうとしたときです。開始されたスレッドは、開始した側のスレッドが
待機したときに初めて動きます。言語仕様はスレッドに実行の順番を保証していないため、この
実行順序はどれも仕様の範囲内です。違いが現れるのは次の 4 種類のプログラムです。

- 待機しないループは、別のスレッドへ交代しません。ただし、別のスレッドが書き込む値を言語
  仕様の定める方法で読むループは、およそ 1000 回に 1 回交代します。該当するのは、`volatile`
  フィールド、アトミッククラス、`synchronized` のメソッドまたはブロック、割り込みの状態を
  読むループです。通常のフィールドだけを読むループ、本体の中でインターフェース型の変数を
  通じて問い合わせるループ（`while (true) { if (future.isDone()) break; }`）、スレッドが
  モニターを保持したまま回るループは、終了するまでほかのスレッドを動かしません。ループの
  中で `Thread.yield()` または `sleep` を呼べば交代します。
- すべてのスレッドが互いを待って進めなくなったプログラムは、標準エラーにその旨のメッセージを
  書き出し、ステータス 1 で終了します。JDK では待ち続けます。該当するのは、2 つのスレッドが
  相手の入ろうとするモニターをそれぞれ保持している場合、シャットダウンしないプールがある
  場合、キャンセルしない `Timer` がある場合です。
- `String.format` または `printf` が呼び出す `toString()` と `hashCode()`、および報告される
  例外の `toString()` は、待機できません。たとえば `synchronized` な `toString()` のモニターを
  スリープ中のスレッドが保持していると、待機が必要になります。このときプログラムは、上と
  同じくメッセージを書き出してステータス 1 で終了します。
- Common Lisp のプログラムが読み込む `main` のない Java ファイルは、スレッドを開始すると
  コンパイル時に拒否されます。

**モニターも `volatile` も使わずに共有したデータの見え方は、ホストに従います。**
インタープリターと JVM では、2 つのスレッドがどちらも使わずに読み書きするフィールドから、
古い値が読まれることがあります。これは言語仕様が許す動作です。`volatile`、`synchronized`、
`Thread.start`、`join` による順序付けは言語仕様のとおりです。

**プールには JDK と異なる点があります。** プログラムが `getQueue()` に直接入れたタスクは、次の
`execute` まで実行されません。スケジュール実行のプールはタスクを独自の表に保持するため、
`getQueue()` は空です。`CompletableFuture.defaultExecutor()` はステージを実行するプールを
返しますが、これは `ForkJoinPool.commonPool()` が返すオブジェクトとは別のものです。
`LongAccumulator` と `DoubleAccumulator` は値を 1 つだけ保持します。JDK はスレッドごとに
値を畳み込んでから合わせるため、渡した値が関数の単位元であることを前提にします。
`ForkJoinPool` は、タスクを実行できるスレッドが空いていなければ
スレッドを開始します。そのため並列度は問い合わせに答えるだけの数値で、`new ForkJoinPool()`
では 2、共通プールでは 1 です。タスクが投げた例外は、そのまま再スローされます。JDK は、
別のスレッドがタスクを実行した場合、元の例外を原因に持つ同じクラスの例外を投げます。

**`ConcurrentHashMap` の走査順は JDK 25 と同じです。** ただし、チェーンが 64 本以上ある
テーブルで、1 本のチェーンに 9 個のキーが入った場合の順序は一致しません。

**スレッドの状態が `BLOCKED` になることはありません。** `getState` で分かるのは、スレッドが
待機中またはスリープ中であることです。モニターに入るのを待っているスレッドの状態は
`RUNNABLE` です。未捕捉例外ハンドラーが呼ばれるのはプログラムが開始したスレッドだけで、
`main` のスレッドでは呼ばれません。`AtomicInteger`、`AtomicLong`、加算器
（`LongAdder`、`DoubleAdder`）、アキュムレーターは `Number` ではありません。

**スレッドの番号は rontolisp 独自のものです。** `threadId()` と `toString()` に含まれる番号は、
プログラムのスレッドを 1 から数えた値です。

## HTTP クライアント

**リクエストは、ターゲットのトランスポートが送ります**
（[`rontolisp:fetch`](../guides/http-fetch.md)）。接続に関する設定はトランスポートが決める
ため、対応するメンバーはありません。タイムアウト（`timeout`、`connectTimeout`）、プロキシ、
認証、クッキーハンドラー、`SSLContext`、WebSocket です。`version(..)` で指定した値は保持され、
問い合わせには答えますが、どのトランスポートもこの値を読みません。応答の `version()` は、
実際に使われたバージョンにかかわらず `HTTP_1_1` です。`User-Agent` を指定していない
リクエストには、rontolisp の `User-Agent` が付きます。

**送れるメソッドは `GET`、`HEAD`、`POST`、`PUT`、`DELETE`、`OPTIONS`、`PATCH` です。** ビルダーが
受け付けたそれ以外のメソッド（`TRACE`、`get`）と `expectContinue(true)` は、リクエストを送る
時点で `UnsupportedOperationException` になります。

**ボディは全体をまとめて読みます。** `send` が戻るのも、`sendAsync` のフューチャーが完了する
のも、応答をすべて受け取ったあとです。そのため、`BodyHandlers.ofInputStream()` が返す
ストリームは、受信済みのボディを読みます。`BodyPublishers.ofInputStream` のストリームは、
リクエストを送る前に終わりまで読まれます。`BodyHandler` は `BodySubscribers` のサブスクライバー
から 1 つを選ぶ関数として書き、リクエストのボディは `BodyPublishers` で作ります。プログラムが
独自に実装したサブスクライバーとパブリッシャーは、使われた時点で
`UnsupportedOperationException` になります。`BodyHandlers.ofString()` は、応答の `Content-Type`
が示す文字セットが UTF-8、ISO-8859-1、US-ASCII のいずれかであればその文字セットで読み、
それ以外は UTF-8 として読みます。`ofString(charset)` には、どの文字セットも指定できます。

**失敗は、トランスポートのメッセージを持つ `IOException` です。** 接続を拒否された場合は、
どのバックエンドでも `ConnectException` になります。JDK にはこのほかにも例外クラスがあります
（`UnknownHostException`、`SSLHandshakeException`）。

**`sendAsync` のフューチャーは、共通プールのスレッドで完了します。** クライアントに
`executor` を指定した場合は、そのエグゼキューターのスレッドで完了します。`.wasm` では
[スレッドが交代しながら実行される](#threads)ため、応答を待つスレッドは、まず実行できる
ほかのスレッドに一度ずつ順番を譲ります。そのあとは、応答をすべて受け取るまで、スリープ中の
スレッドを含めてほかのスレッドは動きません。

**`URI`** は、JDK と同じ検査を同じメッセージで行います。ただし、角括弧で囲んだアドレスに
ついては、使われている文字だけを検査します。`hashCode` の値は JDK と一致せず、`compareTo` は
文字列としての順序で比較します。複数の部分を取るコンストラクタ、`normalize`、`relativize`、
`toURL`、`toASCIIString` はありません。

## HTTP サーバー

**サーバーは、ターゲットのトランスポートを通してリクエストを受けます。** これは Clack
アプリケーションが使うものと同じトランスポートです。接続に関する設定はトランスポートが
決めるため、対応するクラスとメンバーはありません。`HttpsServer`、`Filter`、
`Authenticator`、`HttpHandlers`、`SimpleFileServer` です。バックログの値は読まれません。
1 つのプログラムが使えるサーバーは 1 つです。サーバーをバインドしているあいだに別の
サーバーをバインドすると、`UnsupportedOperationException` になります。

**`wasmtime serve` では、ポートとプログラムの寿命をホストが管理します。** `create` に渡した
アドレスはバインドされず（使われるのは `--addr` です）、`getAddress()` は渡されたアドレスを
そのまま返し、`stop` は何もしません。ホストは、必要に応じてプログラムのインスタンスを起動し、
`main` を最後まで実行します。wasmtime 49 は、1 つのインスタンスに最大 128 個のリクエストを
渡し、リクエストが 1 秒間来なければそのインスタンスを破棄します。そのため、static
フィールドの値が保たれるのは、そのインスタンスが破棄されるまでです。`wasi:http` は、接続元の
アドレスも、リクエストを受けたポートもハンドラーに伝えません。`getRemoteAddress()` は
`0.0.0.0:0` を返し、`getLocalAddress()` のポートは 80 です。

**`--native` の実行ファイルとコンポーネントは、`main` が戻ってからリクエストに応答します。**
スレッドが 1 本しかないからです。`start()` のあとで `main` が待ち続けるプログラムは、
[すべてのスレッドが待機した](#threads)プログラムとして拒否されます。ほかのスレッドを待つ
ハンドラーも同じように拒否されます。ハンドラーの中の `Thread.currentThread()` は、`main` を
実行したスレッドを返します。実行ファイルは、ポート 0 を指定して得たポート番号を
知ることができず、`getAddress()` は 0 を返します。

**ハンドラーを実行するのは、エグゼキューターのスレッドではなく、トランスポートのスレッドです。**
エグゼキューターのないサーバーは、JDK と同じく、ハンドラーを 1 つずつ実行します。
エグゼキューターを設定したサーバーでは、インタプリタと JVM がリクエストごとに別のスレッドで
ハンドラーを実行しますが、エグゼキューター自体には何も渡されません。`.wasm` は、
エグゼキューターの有無にかかわらず、ハンドラーを 1 つずつ実行します。

**チャンクで送るボディが書き込みのたびに送られるのは、`wasmtime serve` だけです。**
`sendResponseHeaders(status, 0)` のあとの書き込みは、`wasmtime serve` では、書き込みが戻った
時点でそのチャンクが送信されています。インタプリタ、JVM、`--native` の実行ファイルは、
ハンドラーが戻ってから、長さを付けてボディ全体を送ります。長さを事前に知らせたボディは、
その長さをすべて書き込んだ時点で送られます。レスポンスの区切り方はトランスポートが決める
ため、ハンドラーが設定した `Content-Length` と `Transfer-Encoding` は捨てられます。

**すべてのリクエストに応答があり、エクスチェンジはハンドラーが戻ると終わります。**
ハンドラーが例外を投げた場合、ヘッダーを送らずに戻った場合、ボディが知らせた長さに満たない
場合は、ボディのない 500 を返します。JDK は、これらの場合に接続を閉じるか、リクエストに
応答しないままにします。ハンドラーが戻った時点で閉じられていないエクスチェンジは、
そこで閉じられます。そのため、あとから別のスレッドで応答することはできません。`stop` の
遅延時間は待ちません。ハンドラーが呼んだ `stop` は処理中のリクエストに応答してからサーバーを
止め、それ以外の場所で呼んだ `stop` は直ちにサーバーを閉じます。`start()` のあとで `main` が
例外を捕捉しなかった場合、プログラムは終了します。

**リクエストは、ハンドラーが動く前にすべて読み込まれています。** ボディは読み込み済みです。
同じ名前で複数回送られたフィールドは、値を `, ` でつないだ 1 つの値になります。チャンクで
送るボディを閉じたあとに書き込むと、`stream closed` というメッセージの `IOException` に
なります。JDK は、この場合に独自のクラスの例外を投げます。

**名前解決は行いません。** ホストを 4 つの数値または `localhost` で書いた
`InetSocketAddress` は `InetAddress` を持ちます。それ以外の名前の `InetSocketAddress` は
未解決のままで、その名前をトランスポートがバインドします。`InetAddress.getByName` などの
名前解決のメソッドはありません。

## 拒否される構文

どれも、構文の名前と位置を示すエラーになります。

- アノテーション型、`assert`。
- 値として使うクラスリテラルと `getClass()`。どちらも、クラスとの比較と `getName()`、
  `getSimpleName()` の直前でだけ読めます。クラスリテラルは `synchronized` 文のロックにも
  なります。プリミティブ型と配列型のクラスリテラル。
- `long`、`float`、`double`、`boolean` に対する `switch`。修飾した `super` を通すメソッド参照と
  フィールド（`Interface.super::m`、`Outer.super.x`）。
- `String`、`StringBuilder`、ボックスを継承するクラス。
- `java.util.concurrent` のうち、[概要](index.md)の表にないもの。優先度順または遅延順の
  キュー、`ConcurrentSkipListMap`、フィールドアップデーターです。`Date` を取る `Timer` の
  `schedule`、スレッドグループ、`Duration` を取る `sleep` と `join` も拒否されます。
- [概要](index.md)の表にないライブラリのクラスとメンバー。ストリーム（`java.util.stream`）、
  `LinkedList`、`LinkedHashMap`、`TreeMap`、`Scanner`、ファイル（`File`、`FileReader`、
  `java.nio.file`）、`java.time`、`BigInteger`、`BigDecimal`、リフレクションなどです。
  `java.net` では、`URI`、サーバーのアドレス、[HTTP クライアント](#the-http-client)以外の
  すべてが該当します。
  `URL`、ソケット、`HttpURLConnection` です。
