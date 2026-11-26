# 言語との差異

サブセットであることは設計上の選択です。サブセットが受け付けるプログラムの動作が言語仕様と
異なる箇所はここに挙げたとおりで、`.wasm` の[スレッド](#threads)と、`.wasm` が単独で書き出す
サロゲートペアの片割れ（後述の文字列の項）を除けば、どのバックエンドでも同じです。

## 動作が異なる点

**文字列はコードポイントの列です。** `length`、`charAt`、`substring` は Unicode の
コードポイントを数えます。言語仕様は UTF-16 のコード単位を数えるので、基本多言語面の外の
文字を含む文字列では結果が変わります。`hashCode` は仕様どおりの値です。`toCharArray` と
`chars()` は UTF-16 のコード単位を返します。それからテキストを作ると（`new String(char[])`、
文字列の連結、コード単位を追加した `StringBuilder` の `toString`）、上位サロゲートの直後の
下位サロゲートは 2 つが表す 1 文字になり、元の文字列に戻ります。`StringBuilder` は 1 つずつ
追加されたコード単位をそのまま保持するので、ペアの 2 つを 1 つずつ追加すると、`length` と
`charAt` は 2 つと数えます。`toUpperCase` と `toLowerCase` は 1 文字を 1 文字に変えます。
`"ß".toUpperCase()` は `ß` です（JDK は `SS` を返します）。相手のないサロゲートは、UTF-8
では 3 バイトになります（JDK は `?` を書きます）。標準出力に書くと、相手のないサロゲートは
`?` になり、続けて出力したペアの 2 つは 2 つが表す文字になります。ここまでは JDK と同じですが、
wasm ではそれぞれが 3 バイトになります。`Character.getNumericValue` は、数を表すが数字では
ない文字（`'Ⅷ'`、`'²'`、`'½'`）に -1 を返します（JDK は 8、2、-2 を返します）。

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
型引数付きの型へのキャストはそのクラスを検査します。呼び出しが名指すメソッドより狭い型を取る
メソッドは、呼び出し先が複数のクラスにありうる場合、JDK のブリッジメソッドと同じく引数を検査
します。`((Comparable) 1).compareTo("a")` は `ClassCastException` を投げます。ラムダは何も検査
せず、呼び出し先が 1 つしかないメソッドも検査しません。

**配列への格納は検査しません。** 配列自身の型より上の配列型を通して格納した値
（`Object[] a = new String[1]; a[0] = 1;`）は、言語仕様では `ArrayStoreException` になる場合でも
格納されます。`Array.set` が参照型の配列へ格納する参照も同じで、JDK はこれを
`IllegalArgumentException`（`array element type mismatch`）にします。

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
を付けてコンパイルしたときに書きます。`printStackTrace()` は、この 1 行を除いた同じ行を、標準エラー出力か、
渡された `PrintStream` または `PrintWriter` に書きます。
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
`protected` のフィールドには、そのクラスのサブクラスならどこからでもアクセスできます（JDK の
クラスのフィールドにはどのクラスからでも）。言語仕様では、インスタンスフィールドにはサブクラス
自身の型の参照を通してだけアクセスできます。`protected` のコンストラクタには、サブクラスから
`new` でアクセスできます。言語仕様では `super(..)` を通してだけアクセスできます。コンパイラが表示する警告のうち、
表示するのは呼び出し、変換、キャスト、可変長引数の
[unchecked の警告](index.md#unchecked-warnings)と、到達しない `catch` 節の警告だけです。

**レコードの `hashCode`** は、コンポーネントを宣言順に `31 * h + hash` で畳み込んだ値です。
言語仕様はこの値を定めていません。`Locale` の `hashCode` は独自の値です。JDK も独自の値を返します。

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

**コレクタが参照をクリアすることはありません。** `WeakReference`、`SoftReference`、
`PhantomReference` は、プログラムが `clear()` か `enqueue()` を呼ぶまで対象のオブジェクトを保持します。
参照を `ReferenceQueue` に入れるのは `enqueue()` だけで、`WeakHashMap` は削除されるまでキーを保持し、
`Cleaner` のアクションは `clean()` を呼んだところで実行されます。どのターゲットでも同じです。仕様は、
回収できるものをコレクタが保持し続けることを許しています。何も回収しないコレクタ
（`-XX:+UseEpsilonGC`）で起動した JDK も同じ答えを返します。`System.gc()` の後に参照を読む
プログラムや、キーを手放した後にマップの大きさを読むプログラムは、JDK の既定のコレクタがクリアした
ものが残っているのを見ます。プログラムが手放すオブジェクトをキーにしたキャッシュは、キーの数だけ
大きくなります。`WeakHashMap` の null キーは最初にたどられます。JDK では、その位置は実行のたびに
変わります。

**`TreeMap` とソートがコンパレータに比べさせる組は JDK と異なります。** 木の均衡の取り方が違い、
オブジェクトのソートは rontolisp 独自のマージソートであるため、呼び出しを数えたり表示したりする
コンパレータには、別の組が別の回数で渡されます。順序とマップの答えは JDK と同じです。マップの
`keySet()`、`values()`、`entrySet()`、`descendingMap()` は呼ぶたびに新しいビューです。JDK のマップは
毎回同じオブジェクトを返します。そのため `m.values().equals(m.values())` は `false` です。

**変更できないコレクションの答えのいくつかは JDK と異なります。** 要素が 1 個か 2 個の
`List.of` のリストは、範囲外の添字を `ArrayIndexOutOfBoundsException: Index 2 out of bounds for
length 2` で拒みます。JDK は `IndexOutOfBoundsException: Index: 2 Size: 2` です。`List.of` のリスト、
`Stream.toList()` のリスト、ギャザラーの窓のイテレータは、`next()` 前の `remove()` で
`IllegalStateException` を投げます。JDK は `UnsupportedOperationException` です。`Set.of` のセットと `Map.of` のマップは、`null` を問われても
答えます（`contains(null)` は `false`）。JDK は `NullPointerException` を投げます。そのマップの
ビューは、何も変えない変更も拒みます。`Arrays.asList` のリストは削除をメッセージなしで拒みます。
JDK のメッセージは `remove` です。`List.of()`、`Set.of()`、`Map.of()` と、空のストリームの
`toList()` が作るリストは、呼ぶたびに新しいオブジェクトです。`AbstractList` 系のリストのイテレータが
末尾を越えると `NoSuchElementException` を投げ、その原因は空です。JDK ではリストの
`IndexOutOfBoundsException` が原因になります。

**リストの一部（`subList`）とリストのイテレータは、背後で行われた変更を JDK と同じように拒みます。**
`AbstractList.modCount` という変更回数を使います。`ArrayList`、`LinkedList`、`Vector` はこれを
更新し、`AbstractList` を継承するプログラムのクラスは、拒んでほしいときに自分で更新します。
リストが別の経路で変更された一部は、そのメンバーで `ConcurrentModificationException` を
投げます。背後で変更されたリストのイテレータも同じです。
`LinkedList` の一部やプログラムのリストの一部は、答えが JDK と異なる点があります。変更された
一部の `subList` は、JDK なら部分リストを作れるところで拒みます。そのような一部のイテレータは、
末尾を越えた `next()` で `ConcurrentModificationException` を投げます。JDK は
`NoSuchElementException` です。`LinkedList` の一部のイテレータは `next()` 前の `remove()` で
`IllegalStateException` を投げます。JDK は `ConcurrentModificationException` です。
一部のクラスは `java.util.AbstractList$SubList` です。JDK の `ArrayList` の一部は
`java.util.ArrayList$SubList` です。`CopyOnWriteArrayList` の一部は、JDK と同じく作られた
ときの配列と比べて背後の変更を拒み、そのイテレータは手元にあった配列を歩きます。

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
`quoteReplacement`、`results`、`splitAsStream` です。`usePattern` と `namedGroups` はありません。
透過境界のリージョンで `lookingAt` または `matches` を呼んだあと、後読みがリージョンより前を
読んでいると、JDK の `start()` は 0 を返します。ここではリージョンの開始位置を返します。

**`Scanner` が数値を読んだあとのマッチはトークンだけです。** `nextInt` から `nextBigDecimal` まで
と、それぞれの `hasNext` のあとの `match()` にはグループがありません。JDK のマッチには、JDK
自身の数値のパターンのグループがあります。`match()` と `toString()` の位置は、スキャナーが保持
する入力の先頭から数えます。`String` ならそのテキスト全体です。JDK は最初の 1024 文字を過ぎると
自身のバッファーの先頭から数えます。`Readable` には読み込みのたびに 1024 文字の空の
`CharBuffer` を渡します。JDK はスキャナー自身のバッファーを、まだ保持している文字の後ろに
位置を置いて渡します。2 回目以降の読み込みでも 1024 文字を求めます。JDK はバッファーの空きの分を
求めます。そのため、先頭 1024 文字より後ろに文字にならないバイトがある `File` の `Scanner` では、
それより前のトークンを JDK とは異なる読み込みの区切りまで返します。

**書式は指定子に達したときにその指定子を拒否します。** JDK は制御文字列全体を先に読むので、拒否された
`Formatter` は何も追記していません。ここではそれより前の部分を追記済みです。`%d`、`%x`、`%o`、`%e`、
`%f`、`%c`、`%b`、`%s` が受け付けないフラグと精度（`%#d`、`%,x`、`%.2d`、`Formattable` でない値の
`%#s`）は拒否しません。相対インデックス `%<s` は受け付けません。変換した引数はパディングを含めて 1 つの
テキストとして追記します。JDK はパディングを 1 文字ずつ追記します。`long` と `Date` の `%tZ` と `%tc` は、
`Date.toString()` と同じ方法でゾーンを書きます。デフォルトのロケールは `Locale.US` で、ロケールなしの
`String.format` に渡した `Formattable` が自分自身を書き込む `Formatter` のロケールもこれです
（[それ以外のロケール](#a-locale-of-any-language)）。

**`MessageFormat` には書式型がありません。** 書式型を指定したパターン（`{0,number}`、
`{0,date}`、`{0,choice,...}`）は、読んだ時点で名前を示す `UnsupportedOperationException`
になります。2^63 以上の `Double` は、読み戻すと同じ値になる最短の桁で書きます。JDK は
別の桁を書くことがあり、`1e23` はここでは `100,000,000,000,000,000,000,000`、JDK では
`99,999,999,999,999,990,000,000` です。`applyPattern` が拒否したパターンは書式を変えません。
JDK では前後 2 つのパターンが混ざった状態が残ります。

**`BigInteger` と `BigDecimal` には素数のメンバーがありません。** `isProbablePrime`、
`nextProbablePrime`、`probablePrime`、乱数のビットを取るコンストラクタは、名前を示して
拒否されます。

**プログラムが持つ tz データベースの地域は、プログラムが名前を書いたものだけです。** オフセットが
変わらないゾーンは、どのプログラムでも使えます。オフセット（`+09:00`、`Z`）、`UTC`、`GMT`、
`UT` とそれにオフセットを付けたもの（`UTC+09:00`）、tz データベースの固定ゾーン
（`Etc/GMT+5`、`Etc/UTC`、`Zulu`）です。`Europe/Paris` のようにオフセットが変わる地域は、
規則ごとコンパイル結果に埋め込みます。埋め込むのは、プログラムが名前を書いた地域だけです。
`ZoneId.of("Europe/Paris")` のように名前が定数なら、その地域 1 つを埋め込みます。
`ZoneId.of("ECT", ZoneId.SHORT_IDS)` と `ZoneRulesProvider.getRules("Europe/Paris", false)`
も同じです。static または private のメソッドが代入せずに `ZoneId.of` に渡す引数は、その
メソッドのすべての呼び出しが渡す定数の地域を埋め込みます（`static ZoneId zone(String id)
{ return ZoneId.of(id); }` の `zone("Europe/Paris")` ならパリ）。それ以外の実行時に決まる
名前（メソッド参照を通した呼び出しを含む）を `ZoneId.of` に渡すか、`SHORT_IDS` 以外の
別名の表を `ZoneId.of` に渡すか、`getAvailableZoneIds()` を呼ぶと、すべての地域を
埋め込みます。
プログラム自身のファイルまたはソースパスのライブラリに書いた `ZonedDateTime.parse` は、
定数の文字列ならそこに名前がある地域を埋め込み（`"2026-07-01T10:15+02:00[Europe/Paris]"`
ならパリ）、実行時に決まる文字列ならすべての地域を埋め込みます。埋め込まれていない地域は未知の名前として扱います。`ZoneId.of` は
`ZoneRulesException` を投げ、`ZonedDateTime.parse` は角括弧内にその名前がある文字列を拒否
します。プログラムのどこかで `ZoneId.getAvailableZoneIds()` を 1 回呼べば、すべての地域が
入ります。`.wasm` のサイズは、最初の地域で約 57 KB、2 つ目以降は 1 つにつき数百バイト、
すべての地域ではさらに約 48 KB 増えます。規則は、rontolisp をビルドした時点の
tz データベースのもので、ホストのものではありません。データベースの更新は、更新後の
rontolisp でコンパイルし直すとプログラムに反映されます。`ZoneRulesProvider` はこの規則だけを
提供します。`registerProvider` と、プロバイダが実装するメソッドはありません。

地域を持つプログラムの `ZoneId.systemDefault()` は、ホストが示すゾーンをプログラムが知って
いればそのゾーンです。ホストが示すゾーンは、環境変数 `TZ` の名前（先頭のコロンは除きます）か、
`TZ` が未設定か空ならリンク `/etc/localtime` の指す先です。`.wasm` は、ホストが `/etc` を
許可していればリンクを読みます。それ以外では `ZoneOffset` で、呼び出した時点でホストのゾーンが
持つオフセットです。このとき `LocalDate.now()` はホストの日付になり、夏時間の有無が現在と
異なる時期の日時も現在のオフセットで読みます。`.wasm` がホストのオフセットを知る方法は
[`decode-universal-time`](../guides/clock-and-random.md) と同じで、分からなければ UTC です。

**`java.util.Date` はゾーンをオフセットで書きます。** `toString` は、ホストが UTC なら `UTC`、
それ以外では `GMT+09:00` のように書きます。JDK はゾーンの略称（`JST`、`EST`）を書きます。
地域の最初の規則より前のオフセットは、ホストの地方平均時です（ニューヨークの 1883 年より前は
`GMT-04:56`）。JDK はその地域の最初の標準時のオフセットを使います。JDK 1.1 から
非推奨のメンバー（`getYear`、`getHours`）は名前を挙げて拒否します。

**フォーマッターが書く語はルートと英語のロケールのものです。** 月、曜日、紀元、四半期、
午前と午後、一日の時間帯、地域化された形式（`ofLocalizedDate` とその仲間）、要求テンプレートの
パターン（`ofLocalizedPattern` とその仲間）、ゾーンの語による名前は、ルートロケール、`ENGLISH`、
`US`、`UK`、`CANADA` について JDK が書くものです（[それ以外のロケール](#a-locale-of-any-language)）。
週は、`UK` では月曜日に始まり最初の週が年の 4 日を含み、それ以外では日曜日に始まり 1 日を含みます
（`WeekFields.of`。[週はその地域のもの](#a-locale-of-any-language)です）。ロケールを指定しない `ofPattern`、地域化された形式、`ofLocalizedPattern`、
ISO の形式は、デフォルトのロケールである `US` を使います。`localizedBy` はそのロケールと ISO 暦を使います。
これらのロケールはどれも、ほかの暦、ゾーン、数字を指定しないからです。ゾーンの語による名前は、
ゾーンの ID と同じく、プログラムが持つゾーン（上記）の中から読みます。JDK がプログラムの持たない
地域として読む名前は、プログラムが持つ地域のうちその名前を書くものとして読むか、読みません。
JDK は、あるゾーンの名前を `ENGLISH` について書いたあと、同じゾーンの `UK` と `CANADA` の名前の
一部を `ENGLISH` のものとして書きます（`CANADA` で `Abidjan Daylight Saving Time` と書いていた
ものを `Abidjan Daylight Time` と書く）。ここで書く名前は、その前に JDK が書くものです。
JDK がオフセットとして書く名前（`GMT-05:00`）は、JDK が動いている時点でそのゾーンが持つオフセット
です。ここでは、ルールと同じく、rontolisp がビルドされたときの tz データベースが与えるオフセットです。
ビルドのあとに来るゾーンのオフセットの変更は、あとのビルドで名前に届きます。
これらのロケールの `DecimalStyle` はどれも標準のもので、`DecimalStyle.getAvailableLocales()`
はこの 5 つを返します。フォーマッターの文字列表現では、
`parseDefaulting` は `Default(field,value)` と表示されます。JDK はオブジェクトの識別子を表示します。
`ChronoField` でないフィールド（週、四半期、ユリウス日、プログラムが書いたもの）は
読んだ順に解決します。JDK の順序は実行のたびに変わります。違いが現れるのは、文字列が曜日を
2 度（名前とロケールの週の中の番号で）与える場合と、そうしたフィールドが 2 つの日付または
2 つの時刻になる場合だけです。

**`java.time` の暦は ISO だけです。** `Chronology.of` はほかの暦を知らず、
`getAvailableChronologies()` は ISO だけを返します。`Duration.dividedBy` は、名前を示して
拒否されます。プログラム独自の暦は `AbstractChronology` を継承でき、日付、日時、ゾーン付き
日時、期間を JDK と同じく持ちます。`withChronology` で指定したフォーマッターは、その暦で読み書き
します。ただし、`WeekFields` の週基準年とユリウス日は ISO の年で読み書きします。
`appendChronologyId` と `appendChronologyText` は名前を示して拒否します。JDK が語を持つほかの
5 つの暦の型（`buddhist`、`japanese`、`roc`、`islamic-umalqura` とその仲間）の暦は、紀元を数で
読み書きします。瞬間とゾーンを日付や時刻と一緒に与える文字列は瞬間の日付と時刻として読みます。
JDK はそれと異なる日付や時刻を拒否します。
フィールドから値を作れなかった文字列のメッセージは、フィールドを決まった順で並べます
（`{Year=2020, MonthOfYear=13}`）。時のない時刻の複数のフィールドが範囲外のときも、決まった
フィールドを先に拒否します。JDK ではどちらの順序も実行のたびに変わります。`ValueRange`、
`ZoneRules`、`ZoneOffsetTransition`、`ZoneOffsetTransitionRule` のハッシュコードは JDK と
異なります。`IsoChronology` と `InstantSource.system()` はオブジェクトとしてハッシュします
（JDK もクラスまたはオブジェクトの識別子から作ります）。

**`Normalizer` は JDK 25 の Unicode データを使います。** それより新しい JDK の Unicode で
追加または変更された文字も、JDK 25 と同じ結果になります。すでにその形式になっている文字列を
`normalize` に渡すと、その文字列自体を返します。JDK は内容の等しい別の文字列を返すことが
あります。

**古い 2 つのクラスは新しいクラスと同じものです。** `StringBuffer` は `StringBuilder` であり、
`getClass().getName()` も `StringBuilder` の名前を返します。`Vector` は `instanceof` で
`ArrayList` と判定されます。範囲外の添字は、JDK と同様にメンバーごとの文言の
`ArrayIndexOutOfBoundsException` で拒否します。負の添字は JDK では配列の容量で表現される文言
(`Index -1 out of bounds for length 10`) ですが、容量を持たないため、ここでは要素数で表現します。

**標準ストリームは文字のストリームです。** `System.in` は、ホストの標準入力の文字を、その
UTF-8 のバイト列として読みます。そのため、テキストでない入力は元のバイト列のままでは
読めません。`System.out` と `System.err` に書いたバイトは UTF-8 のバイトとして扱い、文字は
その最後のバイトが書かれたときに出力します。`System.in` から複数のバイトを読むと、最初の
1 バイトを待ち、その時点で読めるバイトまでを返します。`.wasm` は読めるバイトの有無をホストに
問い合わせられないため、行末まで読みます。`System.out` と `System.err` は差し替えられず
（`System.setOut`）、`close()` の後も出力できます。閉じた `System.in` は入力の終わりに
あるので、その上に作り直した `Scanner` にはトークンがありません。JDK でも同じ結果ですが、JDK
では読み込みが `IOException` を投げます。

**文字セットは 6 つです。** デフォルトの `UTF-8` と、`ISO-8859-1`、`US-ASCII`、`UTF-16`、
`UTF-16BE`、`UTF-16LE` です。それ以外の名前は `UnsupportedEncodingException` または
`UnsupportedCharsetException` になります。UTF-8 または UTF-16 として不正なバイト列は U+FFFD に
デコードします。U+FFFD の個数は JDK 25 と同じです。`UTF-16` の `PrintStream` の `charset()` は、
最初の出力のあと、同じ名前の別のオブジェクトを返します。

**アノテーションは JDK と同じように読みます。** `@Retention(RetentionPolicy.RUNTIME)` を付けて
宣言したアノテーション型のアノテーションで、プログラムまたはソースパスにあるライブラリのクラス、
フィールド、メソッド、コンストラクタ、引数、レコードのコンポーネント、列挙定数、パッケージ
（プログラムのソースツリーにある `package-info.java`）に書かれたものは、`getAnnotation`、
`getAnnotations`、`getDeclaredAnnotations`、`getAnnotationsByType` などで読めます。読めるのは、
型の `@Target` がその種類の宣言を挙げているか、`@Target` がない場合です。`@Repeatable` な型を
繰り返したアノテーションはコンテナー型のアノテーション 1 つになり、クラスは上位クラスが持つ
`@Inherited` な型のアノテーションを自分のものより前に持ちます。JDK のクラスとそのメンバーは
JDK 25 と同じアノテーションを持ちます（`Runnable` の `@FunctionalInterface`、
`new Integer(int)` の `@Deprecated(since="9")`、JDK 自身の `@jdk.internal.ValueBased` など）。
要素は書かれた値または型の既定値を返し、配列は毎回新しい配列です。`equals` と `hashCode` は
`Annotation` が定める値を返し、`toString` は JDK と同じ文字列を返します。要素の順序も JDK と同じで、
既定値を持つ要素が先に並びます。ただし、既定値を持つ要素のうちハッシュマップの同じバケットに入る
2 つは宣言した順に並び、JDK では VM の持つ順序になります。読み出すたびに新しいオブジェクトを
返し、JDK のように毎回同じオブジェクトを返すことはしません。プログラム自身のコードがアノテーションを
読まず、一覧も取らない場合（Servlet コンテナーがサーブレットを見つけるときのように、ライブラリが
自分のためにアノテーションを読む場合）には、アノテーションの `equals` と `hashCode` は
オブジェクト自体を比べ、`toString` は実行時に拒否されます。型の `@Target` が挙げていない種類の
宣言に付いたアノテーションは、javac と違って拒否せず、実行時にも保持しません。

**宣言のジェネリックな型は、宣言に書かれたとおりです。** `getGenericSuperclass`、
`getGenericInterfaces`、`getTypeParameters`、`getGenericReturnType`、
`getGenericParameterTypes`、`getGenericExceptionTypes`、`getGenericType`、`toGenericString` は、
プログラムのクラス、到達可能性メタデータが宣言するライブラリのクラス、JDK のクラス（JDK 25 と同じ。
クラス自身の分と、メンバーを答えるクラスではメンバーの分）について、宣言が名指す型を返します。
`ParameterizedType`、`TypeVariable`、`WildcardType`、`GenericArrayType` の文字列、等価性、
ハッシュ値は JDK と同じで、ジェネリックな型でなければクラスオブジェクトを返します。javac が
ジェネリックなシグネチャを残す内部クラスや列挙型のコンストラクタでは、JDK と同じように、
コンパイラが加える引数を除きます。これらの型はサブセット独自のクラスのオブジェクトで、問い合わせる
たびに新しく作ります。`getRecordComponents()` は、レコードのコンポーネントを、型、シグネチャ、
アクセサー、アノテーションとともに返します。引数（`getParameters()`）の名前は、`-parameters` を
付けない javac が残すとおりです。レコードの正規コンストラクタでは書かれた名前、それ以外は `arg`
と位置です。修飾子も javac が記録するとおりで、内部クラスの外側のインスタンス、列挙型の名前と
序数、列挙型の `valueOf` の引数に付きます。

**プログラムが計算した名前でクラスを見つけられるのは、索引に載っているクラスと、到達可能性
メタデータが宣言するクラスだけです。** どのターゲットにも、探す先のクラスファイルがないためです。
実行時に計算した名前に対する `Class.forName` は、アノテーション付きクラスの索引に載っているクラスと、
GraalVM の到達可能性メタデータが宣言するクラス
（[到達可能性メタデータによるリフレクション](index.md#reflection-from-reachability-metadata)）を
見つけ、それ以外の名前では GraalVM の `native-image` と同じく `ClassNotFoundException` になります。
定数の名前に対しては、その名前のクラスを見つけます。索引は、プログラムがクラスローダーに定数の名前
`META-INF/annotations/<アノテーション型のバイナリ名>` で問い合わせるリソースです。rontolisp は、
その名前のファイルがあればそれに加えて、プログラムのソースツリーのクラスを載せた索引を 1 つと、
ソースパスのルートごとの索引を 1 つずつ答えます。1 行に 1 つのバイナリ名です。載るのは、
そこにあってそのアノテーションが付いたクラスのすべてで、コンパイル時に読みます。プログラムが
名前を挙げていないファイルのクラスも含みます。これにより、ライブラリは JDK の上で動かすのと
同じコードでアノテーション付きのクラスを見つけられます。JDK の上ではクラスパスを読みます
（[Servlet アプリケーション](servlet.md#annotations)）。このサブセットにないシステムプロパティ
（`java.class.path`）は `null` です。ライブラリが `if (System.getProperty(..) != null)` の中で
行う処理はコンパイルしません。

**クラスオブジェクトはクラスが何であるかを答え、プログラムのクラスと JDK のクラスのメンバーを
返します。** `Class` は、名前、修飾子、スーパークラス、宣言するインターフェース、値がインスタンスか
どうかの検査、キャスト、列挙型の定数、メンバーを返します。プログラムのクラスが宣言するメンバー
（`getDeclaredMethods`、`getDeclaredFields`、`getDeclaredConstructors`）は、クラスのソースが宣言する
ものと、言語があわせて宣言するもの（列挙型の `values` と `valueOf`、定数の名前と序数を先頭に取る
列挙型のコンストラクタ、レコードのアクセサー、`toString`、`hashCode`、`equals`、正準コンストラクタ、
デフォルトコンストラクタ、外側のインスタンスを先頭に取る内部クラスのコンストラクタ）です。それぞれ
JDK と同じ型、修飾子、文字列表現を返します。JDK の順序は JDK 独自のものであり、JDK の配列には
コンパイラーが追加するメンバー（ラムダ式の本体、ブリッジ、列挙型の `$values`）も含まれます。JDK が
プログラムのクラスのブリッジを並べるところで、`getMethods` はブリッジが表すメソッドを並べます。
ローカルクラスと匿名クラスのコンストラクタは、読み取るローカル変数を並べません。名前と型での検索、
呼び出し（`invoke`、`newInstance`）、フィールドの `get`、`set`、型ごとの読み書きは JDK と同じです。
呼び出しは動的に振り分けられ、引数はそれぞれボックス化を解いて仮引数の型へ拡大し、答えはボックス化
します。オブジェクトが `null` か別のクラスのものであるとき、引数の数や型が合わないとき、final の
フィールドのとき、呼び出し元のクラスがそのメンバーを使えず `setAccessible(true)` もしていないときは、
JDK と同じ例外と文言で拒否されます。別のパッケージの protected のメンバーを使えるのは、呼び出し元の
クラスがメンバーのクラスの下にあり、使う対象のオブジェクトが呼び出し元のクラスの下にあるときです
（protected のコンストラクタは使えません）。
名前はプログラムが実行時に計算する値なので、プログラムがクラスオブジェクトを持つプログラムのクラスに
ついて、すべてのメンバーをコンパイル結果に含めます。呼び出すのは、呼び出しが届きうるものだけです
（引数を渡す呼び出しは、プログラムにそのような呼び出しがある場合）。
JDK のクラスは、JDK 25 のリフレクションが並べるとおりの公開メンバーと protected のメンバー（名前、
型、修飾子、例外、ブリッジ、`getMethods`、`getFields`、クラスの修飾子）を返します。対象は、
プログラムがクラスオブジェクトを持つクラス（クラスリテラル、値のクラス（`getClass()`）、名前で
見つけたクラス）と、プログラムがそこから上へ読むクラス（`getSuperclass`、`getInterfaces`）です。
private のメンバーとパッケージのメンバーは、どのプログラムも使えないので含めず、メンバーは名前の
順に並べます。リフレクションで見つけたメンバーの型としてだけ持つクラスオブジェクトはメンバーを
返しません。`Class` と `java.lang.reflect` のクラス、ソースパスにあるライブラリのクラスのうち
到達可能性メタデータが宣言しないもの、JDK のパッケージにあるこのサブセット独自のクラス
（`List.of(..)` が作るものなど）も同様で、問い合わせると実行時に拒否されます
（`UnsupportedOperationException`）。プログラムが `getInterfaces` を通してだけ届くインターフェースも、
プログラム自身がそのクラスオブジェクトを持たないので、名前による検索（`getDeclaredMethod(name)`、
`getDeclaredField(name)`）を同じく拒否します。そのため、値のクラスのインターフェースをたどる
テンプレートエンジンは、そこにある default メソッドを見つけません（jmustache でリストの
`{{stream}}`）。ライブラリのクラスの `getDeclaredMethods` はメソッドを名前で
返し、`getDeclaredConstructor()` は引数のないコンストラクタを返します。メタデータが宣言する
ライブラリのクラスは、プログラムのクラスと同じようにメンバーを返し、メタデータが宣言するメンバーを
実行します。それ以外のメンバーを使うと、`native-image` と同じく実行時に拒否されます
（`UnsupportedOperationException`、リフレクションからしか使われないメンバーとして）。項目の
`typeReached` の条件は、プログラムがどこかでそのクラスに到達すれば満たされます。`native-image` は
この条件を実行時に確かめます。
JDK のクラスのメンバーがリフレクションで動くのは、メンバーが実行するものがコンパイル結果にある
ときです。プログラムが呼び出すメソッド、呼び出しが実行しうる本体がすべてプログラムにある
インスタンスメソッド、プログラムがインスタンスを作るコンストラクタ、定数、プログラムが初期化する
クラスの static フィールド、インスタンスフィールドがこれにあたります。メンバーを定数で名指す検索
（`String.class.getMethod("length")`）は、そのメンバーをプログラムに含めます。GraalVM の native
image も、こうした検索が名指すものを保持します。到達可能性メタデータの項目がメンバーを名指す場合も
同じです。それ以外の使い方は、リフレクションからしか使われない
メンバーとして実行時に拒否されます（`UnsupportedOperationException`）。このサブセットのライブラリに
ないメンバーも同様です。`Object` の `equals`、`hashCode`、`toString` と、`Enum` の `name`、
`ordinal`、`compareTo` は、どこで見つけても動きます。そのため jmustache は、`String` の
`{{length}}`、リストの `{{size}}`、列挙型の定数の `{{name}}` を JDK と同じように見つけます。
プログラムがどこからも呼ばないメンバーを名指すテンプレート（`{{blank}}`）は、jmustache 自身の例外で
失敗します。JDK のクラスの公開されていないメンバーや、公開されていないクラスのメンバーへの
`setAccessible(true)` は、JDK と同じ文言の `InaccessibleObjectException` になります（無名モジュールの
識別子は、このサブセットが割り当てる数です）。`trySetAccessible()` は `false` を返します。
次のものも実行時に拒否されます。`Method` を通した `getClass` の呼び出し、ローカルクラスと匿名
クラスの `newInstance`、メソッドに名前と宣言するクラス以上を問い合わせないプログラムでの、
`getDeclaredMethods` が返したメソッドへのそれ以外の問い合わせ。リフレクションで書き込んだ final の
フィールドは、定数であっても、プログラムが読むところで読み直します。JDK のコンパイラーは定数を
それぞれの読み出しに埋め込みます。JDK のクラスの `getInterfaces()` は、このサブセットのクラスのインターフェースのうち
プログラムにあるものを返します。
JDK のクラスのスーパークラスは、このサブセットでの宣言に従います。
`StringBuilder.class.getSuperclass()` は `Object` です（JDK では、間に JDK 独自のクラスが
あります）。`isAssignableFrom` は、このサブセットのクラスが宣言するインターフェースで判定します
（`String` は `Comparable` であり `CharSequence` です）。`o.getClass().isArray()` は `o` が配列か
どうかを答え、ラムダ式でも答えます。それ以外の形でラムダ式の `getClass()` を読むと実行時エラーに
なります。`synchronized (Name.class)` が保持するモニターは、そのクラスの
`static synchronized` メソッドが保持するモニターであり、変数に入れたクラスオブジェクトの
モニターとは別のものです。

**メソッドハンドルはクラスオブジェクトが答えるメンバーであり、ハンドルの型のとおりに使います。**
ルックアップ（呼び出したクラスのアクセス権を持つ `MethodHandles.lookup()`、または
`publicLookup()`）は、メソッドを名前、引数の型、戻り値の型で、そのクラス、上位クラス、
インターフェースの順に探し、コンストラクタを引数の型で探します。探す対象はリフレクションが
答えるメンバー（上記）で、ルックアップのクラスが使えるかどうかもそこで決めます。`invokeExact` と
`invoke` の型は、コンパイラが呼び出しに与える型です。引数の静的な型（`null` は `Void`）と、
文なら `void`、キャストの被演算子ならキャストの型、それ以外は `Object` を返す型です。
`invokeExact` は型の異なるハンドルを拒否し、`invoke` は `asType` と同じく各引数と結果を変換
します。可変長引数のハンドルは、固定の引数より後ろの引数を配列にまとめます。拒否は JDK と同じ
例外と文言です。ただし JDK が自身のコードの内容で書く `NullPointerException` の文言は除きます。
JDK のクラスのメンバーは、リフレクションで実行できる場合に実行できます。定数で名指す
ルックアップ（クラスリテラル、名前、クラスリテラルの `MethodType.methodType`）は、その
メンバーをプログラムに含めます。同じ型を持つ 2 つのメソッド型は等しいものの、同一のオブジェクトでは
ありません。仕様はこれを許しています。

**リソースはコンパイル時に見つけます。** プログラムが読む内容はコンパイル時のファイルの内容です。
実行時に組み立てた名前は、ソースファイル（`.java`）、クラスファイル、名前がドットで始まる
ファイルとディレクトリを見つけません（ランチャーは見つけます）。ローダーが見つけうるファイルがプログラムに
含められる量を超えるときは、コンパイル時に拒否します（[リソース](index.md#resources)）。
ディレクトリのストリームが読む名前は文字の順に並びます（ランチャーはホストのデフォルトロケールの
照合順に並べます）。返すストリームは `ByteArrayInputStream` です。
JDK のクラスは、JDK 自身のものも含めてリソースを見つけません。プラットフォームローダーも
見つけません。リソースの URL はコンパイル時のファイルを指し、出力に含めた内容を読みます。それ以外の
`URL` には読むものがなく、`openStream` は `FileNotFoundException` を投げます。リソースの URL の
テキストから作り直した URL も同じです。`Class<?>` が示すサービスは、プログラムが持つクラスのものです。
`ResourceBundle.getBundle` が定数の基底名で見つけるバンドルクラスは、自身の名前のファイルにある
クラスか、そのメンバーです。`Control` の有効期間は、`TTL_DONT_CACHE` でなければバンドルを
保持し続けます。`needsReload` は呼びません。`Properties.loadFromXML` は JDK の
`InvalidPropertiesFormatException` と同じ文言で文書を拒否しますが、原因の例外を持ちません。
`Properties.store` が書く日付の行は `java.util.Date` のテキストです（前述）。

**ストリームは、結果を求めたスレッドで順に実行されます。** `parallel()` と
`parallelStream()` は記録されるだけです（`isParallel()` は `true` を返します）。要素は
ソースの順に 1 つずつ流れ、コレクターの `combiner` と、`collect`、`reduce` の第 3 引数は
呼ばれません。`flatMap` の中で作ったストリームは、最後の要素を取り出したとき、または
ストリームがそれ以上要素を求めなくなったとき（`findFirst`、`limit`、マッチ、`takeWhile`）に
閉じられます。ここまでは JDK と同じです。後の段が例外を投げた場合は閉じられません。JDK は
この場合も閉じます。`iterator()` と `Stream.concat` は、このストリームの要素を 1 つずつ
取り出します。JDK は全部を取り出してから最初の要素を渡します。
コレクターの `finisher` は、`characteristics()` の内容にかかわらず必ず呼ばれます。
プログラム自身のクラスのコレクターから作ったコレクター（`mapping`、`groupingBy`）は、
元のコレクターの特性を引き継ぎません。このサブセットのクラスのスプリッテレーターは分割しません。
`trySplit` は `null` を返します。スプリッテレーターで読んでいる途中にコレクションを変更すると、
次の要素を渡す前に `ConcurrentModificationException` が投げられます。JDK はその要素を
渡してから投げます。

**`mapMulti` とギャザラーは、1 つの要素に対して渡した要素を、後の段が求めるまで保持します。**
`mapMulti` の関数は、1 つの要素に対する処理を最後まで実行します。後の段が最初の要素を
受け取るのはその後です。JDK では、関数が要素を渡すたびに後の段が受け取り、`limit` を
超えた分も受け取ります。ギャザラーの `push` は、ストリームが要素を求めなくなるまで `true` を
返し、`isRejecting()` は `false` を返します。JDK では、後の段が必要な数を受け取った時点で、
1 つの要素を処理している途中でも値が変わります。そのため、`push` が `false` を返すまで
渡し続けるギャザラーは、ここでは終了しません。ストリームが渡す要素は JDK と同じです。
`Gatherers.mapConcurrent` が結果を渡すのは、実行中のマッピングが上限の数に達したときと、
ストリームが終わったときです。JDK は、終わった結果をそれより早く渡すことがあります。

**ストリームの `spliterator()` が報告するのは、順序と大きさです。** 特性は `ORDERED` で、
ストリームが要素数を知っている場合は `SIZED` と `SUBSIZED` が加わります。見積もりは、その
要素数か `Long.MAX_VALUE` です。JDK は、段を通して追跡している内容も報告します。`DISTINCT`、
`SORTED`、ソースの `NONNULL`、`IMMUTABLE`、`CONCURRENT` を報告し、`HashSet` のストリームには
`ORDERED` を付けず、`filter` の後ではソースの見積もりを返します。また JDK の `sorted()` は、
すでに整列しているストリーム（`IntStream.range`）では要素を先に取り出しません。ここでは
取り出します。`StreamSupport.stream(s, false).spliterator()` は `s` ではなく、別の
スプリッテレーターです。

**ホストへの問い合わせには決まった値を返します。** `Runtime.availableProcessors()` は 1 です。
`System.getProperty` は `line.separator`、`file.separator`、`path.separator` と、エンコーディング
（`file.encoding`、`native.encoding`、`stdin.encoding`、`stdout.encoding`、`stderr.encoding`。
どれも `UTF-8`）に答えます。定数で指定した `user.dir` は作業ディレクトリです（[ファイル](#files)）。
それ以外のキーは、`java.io.tmpdir` も含めて `null` です。`System.gc()` は何もしません。
`System.getenv(name)` はホストの環境変数を返します。`.wasm` が読めるのは、ホストが渡した
変数です（`wasmtime run --env NAME=value`）。すべての変数を返す `getenv()` はありません。

**アサーションは有効になりません。** `assert` 文はコンパイル時に検査しますが、どちらの式も
評価しません。`-ea` を付けずに起動した JDK と同じです。

## 任意の言語のロケール

**任意の言語のロケールを作れます。ロケールはルートか英語のロケールと同じように書くか、拒否します。**
`Locale.forLanguageTag`、`Locale.of`、コンストラクター、ビルダーは、JDK と同じく任意の言語、
文字体系、国、バリアント、拡張のロケールを作ります。ロケールが書くもの（`String.format`、`printf`、
`Formatter` の数値と語、`java.time` のフォーマッターの語とパターン、`DecimalStyle.of`、
フィールドや暦の名前、`Scanner` が読み `MessageFormat` が書く数値）は、`ROOT` と、JDK がデータを
持たない言語（`xx`、`tlh`）ではルートのもの、`ENGLISH`、`US`、`UK`、`CANADA` では英語のものです。
それ以外のロケール（`fr`、`en-AU`、文字体系、バリアント、`-u-nu-arab` のような Unicode
キーワードを持つもの）は、プログラムがそのロケール自身のデータを必要とする位置で、名前を示す
`UnsupportedOperationException` で拒否します。`String.format(Locale.FRANCE, "%s", s)` と
`DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.FRANCE)` は書き、
`String.format(Locale.FRANCE, "%,d", n)` と `ofPattern("MMMM", Locale.FRANCE)` は拒否します。
書式のうちデータを必要とする変換は `%d`、`%e`、`%f`、`%g`、`%t` と、トルコ語、
アゼルバイジャン語、リトアニア語での大文字の変換です。

**週はその地域のものです**（`WeekFields.of`、フォーマッターの週の文字）。国、または `-u-rg-`
キーワードが示す地域の週で、最初の曜日は `-u-fw-` キーワードが示すものです。国のないロケールは、
JDK と同じく日曜日に始まり 1 日を含みます。**ロケールを取る `toUpperCase` と `toLowerCase`** は
ルートと同じように文字を変えますが、トルコ語とアゼルバイジャン語（`I` と `ı`、`İ` と `i`）、
リトアニア語（アクセントの下でも `i` の点を残す）は別です。文字の結合クラスが必要な場合
（ほかの結合文字のあとの上の点）は拒否します。**`ResourceBundle`** は、デフォルトの `Control` が
挙げる任意のロケールの候補からバンドルを探します。`Chronology.ofLocale` と `localizedBy` は
ISO の暦を使います。`-u-ca-` キーワードが示す暦（`japanese`）、数字（`-u-nu-`）、地域
（`-u-rg-`）、ゾーン（`-u-tz-`）は拒否します。`DecimalStyle.getAvailableLocales()` は 5 つの
ロケールを返します。

## ファイル

**ファイルはホストのものです。`.wasm` では、ホストが事前に開いたディレクトリだけを使います。**
相対パスは作業ディレクトリを基準に解決します。`.wasm` は最初にプリオープンされたディレクトリを
作業ディレクトリとみなします。そのディレクトリが相対名で開かれている場合（`wasmtime run --dir .`）、
`getAbsolutePath`、`getCanonicalPath`、`toAbsolutePath`、`toRealPath` は相対パスを、`toURI` と
`toUri` は `file:` とそのパスからなる URI を返し、`System.getProperty("user.dir")` は null です。
ディレクトリを指定しない一時ファイルは `/tmp` に作ります。`.wasm` が `/tmp` に届くのは、ホストが
それをプリオープンした場合だけです。`.wasm` は、絶対パスを指すシンボリックリンクをたどらず、
読まず、作りません。そうしたリンクの先は存在しないものとして扱います（`exists` は false、属性は
`AccessDeniedException`）。そうしたリンクの `readSymbolicLink`、絶対パスを指す
`createSymbolicLink`、そうしたリンクを `NOFOLLOW_LINKS` 付きで `copy` することは
`AccessDeniedException` になります。

**ファイルの種類、サイズ、時刻は、どのターゲットでもホストの `stat` が返すものです。`.wasm` では
その一部しか得られません。** WASI の `stat` にはファイルの作成時刻がないため、`.wasm` の
`creationTime` は最終更新時刻です。作成時刻を記録しないファイルシステムで JDK が返す値と同じです。
`.wasm` は 1970 年より前の時刻を書き込めません。そうした時刻は `1970-01-01T00:00:00Z` として書き込み、
Preview 1 では読み出しもその値になります。`fileKey()` はどのターゲットでも null です。
テキストで名前を指定できる属性は、`basic` ビューと、インタープリターと JVM では `posix`、`unix`、
`owner` ビューのものです。Unix ホストの JDK には `dos` と `user` もあります。この 2 つはここでは
`UnsupportedOperationException`（`View 'dos' not available`）になり、
`supportedFileAttributeViews` にも含まれません。`getFileAttributeView` は `basic` ビューと、
ある場合は `posix` ビューと `owner` ビューを返し、ほかのビューには null を返します。

**パーミッションビット、所有者、ディスクの容量は、インタープリターと JVM ではホストのものです。
`.wasm` にはどれもありません。** インタープリターと JVM では、パスに対して何ができるかはホストの
`access(2)` が答えます。パーミッションビットの読み書きができ、作るファイルとコピーにも与えます
（JDK と同じく umask を除いた値）。一時ファイルは `rw-------`、一時ディレクトリは `rwx------` です。
所有者とグループの名前はホストのユーザーデータベースのものです。容量はパーティションのものです。
WASI にはパーミッションビットも所有者も `access` も `statvfs` もないため、`.wasm` は POSIX ビューの
ないファイルシステム、または判断できない場合の JDK と同じように答えます。`getOwner`、`setOwner`、
`PosixFileAttributes` を取る `readAttributes` は `UnsupportedOperationException` です。`posix`、
`unix`、`owner` ビューは名前で指定できず、`getFileAttributeView` はそれらに null を返し、
`supportedFileAttributeViews` は `basic` だけを返します。`getPosixFilePermissions` と `setPosixFilePermissions` は `UnsupportedOperationException` です。
ファイルを作るときの `posix:permissions` 属性は拒否します（`'posix:permissions' not supported as
initial attribute`）。ファイルとコピーのビットはホストの既定値です。`canExecute` と `isExecutable`
は false、ディレクトリの `canWrite` と `isWritable` は false、ファイルの `canRead` と `canWrite`
は開けるかどうか、設定するメソッド（`setReadable` など）は false です。`getTotalSpace`、
`getFreeSpace`、`getUsableSpace` は 0 です。

**動作の異なるメンバーと、ないメンバーがあります。** `RandomAccessFile.setLength` でファイルを短くすると、
残すバイトを書き直します。
`StandardOpenOption.SYNC`、`DSYNC`、`SPARSE` は何も変えません。
`Path` はすべて、このサブセット独自の 1 つのクラスです（`getClass()` が返すもの）。ただ 1 つの
`FileSystem` も同様です。角かっこの中が空のグロブ（`[]`、`[!]`）は、このサブセット独自の文言で
拒否します。次のメンバーはなく、名前を示して拒否します。`Path.register`。
`Files.createLink`。`FileSystem.getUserPrincipalLookupService`、`dos` ビューと `user` ビュー
（`DosFileAttributes`、`UserDefinedFileAttributeView` など）。
`FileDescriptor` とチャネル。
ファイルに対する `FileSystems.newFileSystem` はプロバイダーを見つけられません
（`ProviderNotFoundException`）。アーカイブをファイルシステムとして読むことはできません。

**ブラウザーでは、ファイルに触れるプログラムと `user.dir` を尋ねるプログラムを実行前に拒否します。**
ページにはファイルシステムがないためです。パスを計算するだけのプログラムは実行します。

## バッファー

**バッファーのクラスはサブセットのものです。** バッファーの `getClass().getName()` は、それを作った
クラス（`java.nio.ByteBuffer`、`java.nio.IntBuffer`）です。バイトバッファーのビューでは
サブセット独自のクラス（`java.nio.ByteBufferAsIntBuffer`）です。JDK は種類ごとの名前
（`java.nio.HeapByteBuffer`、`java.nio.DirectByteBuffer`、`java.nio.ByteBufferAsIntBufferB`）を
返します。`toString()` は JDK と同じく種類の名前を示します。ダイレクトバッファーも配列ですが、
その配列は渡しません。文字セットのコーダーのクラスもサブセット独自のもの
（`java.nio.charset.StandardCharsetEncoder`）です。

**ネイティブのバイト順はどのターゲットでもリトルエンディアンです。** `ByteOrder.nativeOrder()` と、
バイトバッファーのビューではないバッファーの `order()` は、wasm モジュールのメモリと同じく
`LITTLE_ENDIAN` です。ビッグエンディアンの機械の JDK は `BIG_ENDIAN` を返します。

**`CharBuffer` はテキストをそのままラップします。** `String` ではない `CharSequence` は `charAt`
で読むので、基本多言語面の外の文字は 1 要素です。JDK では 2 要素です。

**`Charset.encode` は `getBytes` と同じバイトを書きます。** 対のないサロゲートは UTF-8 では
3 バイトです（[動作が異なる点](#what-differs)）。JDK は `?` を書きます。`CharsetEncoder` は
JDK と同じく、これを不正な入力とみなして置換値の `?` を書きます。

**ダイレクトバッファーは、どの単位サイズでも割り切れるアドレスから始まります。**
ダイレクトバッファーの `alignmentOffset` と `alignedSlice` はそのように答えるので、16 バイト
までの単位では JDK と同じ値になります。それより大きい単位では、JDK の値はバッファーを
確保した位置によって変わります。ヒープバッファーは JDK と同じく 1 バイトの単位にだけ答え、
それより大きい単位は拒否します。

**次のものはありません。** 名前を示して拒否します。チャネルです。

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
  読むループです。一方、自身の進み方で終わるループ（要素を取り出しながらイテレーターを
  たどるループ、上限まで添字を増やすループ）と、条件と脱出がループ内で計算するローカル変数
  だけで決まるループは、本体で何を呼んでも交代しません。通常のフィールドだけを読むループ、
  本体の中でインターフェース型の変数を通じて問い合わせるループ
  （`while (true) { if (future.isDone()) break; }`）、そうした読み出しが `throw` で投げる
  例外を作る処理の中にしかないループ（`SQLException` のコンストラクターは `DriverManager` の
  ログライターを読みます）、スレッドがモニターを保持したまま回るループも同じく交代しません。これらのループは、終了するまでほかのスレッドを動かしません。
  ループの中で `Thread.yield()` または `sleep` を呼べば交代します。
- すべてのスレッドが互いを待って進めなくなったプログラムは、標準エラーにその旨のメッセージを
  書き出し、ステータス 1 で終了します。JDK では待ち続けます。該当するのは、2 つのスレッドが
  相手の入ろうとするモニターをそれぞれ保持している場合、シャットダウンしないプールがある
  場合、キャンセルしない `Timer` がある場合です。`wasmtime serve` の下でリクエストを受ける
  プログラムは、この形では終了しません。待機中のリクエストが、次のリクエストの処理を
  待っている場合があるからです（[HTTP サーバー](#the-http-server)）。
- `String.format` または `printf` が呼び出す `toString()` と `hashCode()`、および報告される
  例外の `toString()` は、待機できません。たとえば `synchronized` な `toString()` のモニターを
  スリープ中のスレッドが保持していると、待機が必要になります。このときプログラムは、上と
  同じくメッセージを書き出してステータス 1 で終了します。
- Common Lisp のプログラムが読み込む `main` のない Java ファイルは、スレッドまたはサーバーを
  開始するとコンパイル時に拒否されます。

**ブラウザでは、スレッドを開始するプログラムを `.wasm` として実行します。** このサイトの
**Run** とプレイグラウンドは、プログラムをブラウザの中のインタープリターで実行します。
ブラウザの中のインタープリターにはスタックが 1 本しかなく、待機中のスレッドを残して別の
スレッドへ交代することができません。そのため、スレッドを開始しうるプログラム全体は、ページの
中で Preview 1 の `.wasm` にコンパイルし、ブラウザがそれを実行します。スレッドは上記のとおり
交代しながら動き、使える機能は Preview 1 のモジュールと同じです。スレッドを開始し、かつ
HTTP リクエストを送るプログラムは、その理由を示すメッセージとともに拒否されます。スレッドを
開始しないプログラムは、ブラウザでも HTTP リクエストを送れます。プレイグラウンドの REPL に
入力したコードはインタープリターで実行するため、REPL ではスレッドを開始できません。`start()` は
メッセージを出して拒否されます。スレッドを開始するコードは、プログラム全体をプレイグラウンドの
**Run source** で実行します。

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

**`ConcurrentSkipListMap` のイテレータは、最後に返したキーの次のキーを読みます。** 先に読んだ
エントリは、その後に削除されていても返し、変更を拒否しません。そうした削除の後に 2 つのキーの間へ
置かれたキーもここでは返しますが、JDK のイテレータは飛ばすことがあります。`compute`、`merge` などに
渡した関数は、マップのモニターの中で 1 回だけ実行されます。

**`ConcurrentLinkedQueue`、`ConcurrentLinkedDeque`、ブロッキングキューのイテレータは、作られた時点の
要素をたどります。** その `remove` は、返した要素をキューから取り除きます。その間にキューが変わり、
その要素が複数あるときは、等しい最初の要素（逆向きにたどるときは最後の要素）を取り除きます。
JDK のイテレータは返した要素そのものを取り除きます。

**`wait` で通知を受けたスレッドの状態は、`WAITING` のままの場合があります。** ほかのスレッドが
保持しているモニターに入るのを待っているスレッドに対して、`getState` は `BLOCKED` を返します。
一方、`wait` で通知を受けてモニターを取り直そうとしているスレッドに対しては、モニターを
取り直すまで `WAITING` または `TIMED_WAITING` を返すことがあります。未捕捉例外ハンドラーが呼ばれるのはプログラムが開始したスレッドだけで、
`main` のスレッドでは呼ばれません。

**フィールドアップデーターは、クラスリテラルと定数の名前から作ります。**
`AtomicIntegerFieldUpdater.newUpdater(Node.class, "next")` は書かれた場所で読まれるので、
クラスはクラスリテラル（キャストで囲んだもの、`(Class<Node>) Node.class` も含みます）、名前は
定数でなければなりません。それ以外の呼び出しは、コンパイル時に名前を挙げて拒否されます。呼び出すクラスから読めないフィールドもコンパイル時に拒否されます
（JDK は呼び出しの実行時に例外を投げます）。フィールドそのものの問題（その名前のフィールドが
ない、型が違う、`volatile` でない、`static` である）は、JDK と同じ例外が呼び出しの実行時に
投げられます。

**スレッドの番号は rontolisp 独自のものです。** `threadId()` と `toString()` に含まれる番号は、
プログラムのスレッドを 1 から数えた値です。

**`System.exit` を呼ぶシャットダウンフックはプログラムを終了します。** 標準エラーに 1 行を書き、
ステータス 1 で終了します。JDK ではフックがそこで永久に待ち、プログラムも終了しません。フックは
追加した順に開始します。JDK はこの順序を保証しません。

## HTTP クライアント

**リクエストは、ターゲットのトランスポートが送ります**
（[`rontolisp:fetch`](../guides/http-fetch.md)）。接続に関する設定はトランスポートが決める
ため、対応するメンバーはありません。プロキシ、認証、クッキーハンドラー、`SSLContext`、
WebSocket です。`version(..)` で指定した値は保持され、問い合わせには答えますが、どの
トランスポートもこの値を読みません。応答の `version()` は、実際に使われたバージョンに
かかわらず `HTTP_1_1` です。`User-Agent` を指定していないリクエストには、rontolisp の
`User-Agent` が付きます。

**時間制限はトランスポートがミリ秒単位で数えます。** `timeout` と `connectTimeout` は、
[`rontolisp:fetch`](../reference/functions/rontolisp-fetch.md) の `:first-byte-timeout` と
`:connect-timeout` です。1 ミリ秒に満たない時間は 1 ミリ秒として扱い、応答のボディの受信に
時間制限はありません。コンポーネントでは、`timeout` の時間を接続に対して一度、応答の開始に
対してもう一度数えます。`wasmtime serve` の下のコンポーネントはリクエストに時間制限を
付けられず、時間制限のあるリクエストは `IOException` になります。
どのビルダーにも時間制限を渡していないプログラムでは、プログラム自身のクラスの
`HttpRequest` が `timeout()` で時間を返すと、リクエストを送る時点で
`UnsupportedOperationException` になります。

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
[スレッドが交代しながら実行されます](#threads)。コンポーネントでは、応答を待つスレッドは
`sleep` で待つスレッドと同じように扱われ、そのあいだもほかのスレッドは実行されます。
`--native` の実行ファイルでは、応答を待つスレッドは、まず実行できるほかのスレッドに一度ずつ
順番を譲ります。そのあとは、応答をすべて受け取るまで、ほかのスレッドは動きません。スリープ中の
スレッドも、プログラム自身のサーバーに届いたリクエストも同じです。そのため、`main` が
自分のサーバーにリクエストを送ると、`--native` では応答を待ち続けます。

**`URI`** は、JDK と同じ検査を同じメッセージで行います。ただし、角括弧で囲んだアドレスに
ついては、使われている文字だけを検査します。`hashCode` の値は JDK と一致せず、`compareTo` は
文字列としての順序で比較します。複数の部分を取るコンストラクタ、`normalize`、`relativize`、
`toASCIIString` はありません。

**`URL`** は、JDK がハンドラーを持つプロトコルについて、JDK と同じ分け方と拒否を同じメッセージで
行います。`equals` はホストを文字列で比較します（JDK はまずアドレスを問い合わせます）。`hashCode` の
値は JDK と一致しません。`URL.of`、コンテキストと文字列を取るコンストラクタ、`openConnection`、
`getContent` はありません。

## HTTP サーバー

**サーバーは、ターゲットのトランスポートを通してリクエストを受けます。** これは Clack
アプリケーションが使うものと同じトランスポートです。接続に関する設定はトランスポートが
決めるため、`HttpsServer` とその設定のクラス、`SimpleFileServer` はありません。バックログの
値は読まれません。1 つのプログラムが使えるサーバーは 1 つです。サーバーをバインドしている
あいだに別のサーバーをバインドすると、`UnsupportedOperationException` になります。

**`wasmtime serve` では、ポートとプログラムの寿命をホストが管理します。** `create` に渡した
アドレスはバインドされず（使われるのは `--addr` です）、`getAddress()` は渡されたアドレスを
そのまま返します。ホストは、必要に応じてプログラムのインスタンスを起動します。wasmtime 51
は、1 つのインスタンスに最大 128 個のリクエストを渡します。同時に渡すのは 16 個までです。
処理中のリクエストがない状態が 1 秒間続くと、そのインスタンスを破棄します。そのため、static
フィールドの値が保たれるのは、そのインスタンスが破棄されるまでです。インスタンスの起動は、
`main` が終わるか、実行できるスレッドがなくなった時点で終わります。それ以降、プログラムが
動くのは、ホストがそのインスタンスのリクエストを処理中として扱っているあいだです。応答を
返したあとのリクエストも、プログラムのスレッドが時刻を待っているあいだ、またはその
リクエストの処理中にホストへ送ったリクエストの応答を待っているあいだは、処理中として
扱われます。したがって、ハンドラーがスケジュールしたタスクは、ハンドラーが応答したあとに
実行されます。最初のリクエストより前からスリープしているスレッドは、リクエストが届いた
ときに再開します。`stop` を呼んだインスタンスは、ホストに破棄されるまで 503 を
返します。`wasi:http` は、接続元のアドレスも、リクエストを受けたポートもハンドラーに
伝えません。`getRemoteAddress()` は `0.0.0.0:0` を返し、`getLocalAddress()` のポートは 80 です。

**スタックが 1 本のターゲットでは、リクエストはプログラムのスレッドの 1 つとして、ほかの
スレッドと交代しながら実行されます**（[スレッド](#threads)）。そのため、`--native` の
実行ファイルとコンポーネントでも、ハンドラーはほかのスレッドを待つことができ、`main` は
`start()` のあとで待ち続けることができます。待機しないスレッドが動き続けているあいだは、
どのリクエストにも応答できません。

- `--native` の実行ファイルが次のリクエストを受け取るのは、実行できるスレッドがなくなった
  ときです。したがって、ハンドラーが待機しないかぎり、リクエストは 1 つずつ処理されます。
  実行できるスレッドも受け取るリクエストもなければ、すべてのスレッドが待機したプログラム
  として拒否されます。
- `wasmtime serve` では、ハンドラーが待機しているあいだ、ホストは同じインスタンスに別の
  リクエストを渡します。スリープ、ほかのスレッドの終了、自分が送ったリクエストの応答、
  別のリクエストの処理のどれを待っていても同じです。

  ```console
  server.createContext("/held", exchange -> {
      RELEASED.await();                     // /release が届くと応答する
      exchange.sendResponseHeaders(204, -1);
  });
  server.createContext("/release", exchange -> {
      RELEASED.countDown();
      exchange.sendResponseHeaders(204, -1);
  });
  ```

  ただし、待機を終わらせるリクエストは、待機しているリクエストと同じインスタンスに届く
  必要があります。どのインスタンスに渡すかはホストが決めます。wasmtime 51 は、すべての
  リクエストが待機中のインスタンスには次のリクエストを渡しますが、複数のリクエストが同時に
  届くと別のインスタンスを起動することがあります。待っているものが届かないリクエストは、
  ホストが打ち切るまで待ち続けます。

**`--no-wasi` のリアクターは、リクエスト 1 つずつ呼び出され、1 回の呼び出しの中では時間が
進みません（ビルドが `--host-timer` を指定した場合を除きます）。** リアクターの時計は、ホストが呼び出しの前に設定した時刻です。呼び出しは、
リクエストのエクスチェンジに応答した時点で戻り、ハンドラーはそれまでプログラムのスレッドを
待つことができます。一方、エクスチェンジに応答する前に、その呼び出しの中で終わらせられない
待機（スリープ、時間制限付きの待機、別のリクエストの処理を待つ待機）があると、インスタンスを
終了させます。`System.exit` と、`main` が捕捉しなかった例外も同じです。このときホストの
呼び出しは失敗し、生成された Worker は `500` を返して、次のリクエストでプログラムを最初から
開始します。呼び出しの中のどのスレッドも応答せず、応答するために時間を待つスレッドもない
エクスチェンジには、呼び出しの終わりに `500` を返します。`--host-timer` を指定すると、ホストが
JSPI を通じてプログラムを待ちます。時刻を待つ待機はホストのタイマーで待ち、どのスレッドも進めない
リクエストはホストで待機し、その間ホストは別のリクエストでプログラムを呼べます。そのため、
あるリクエストは別のリクエストの処理を待てますし、何も応答しないエクスチェンジは JDK と同じく
開いたまま残ります。スレッドが動くのは、ホストがリクエストを処理中の間です。最後の応答のあとで
待機が終わるスレッドは、次のリクエストで動きます。プログラムの出力は、ビルドが `--host-output` を
指定しない限り捨てられます。指定すれば出力は Worker のログになり、インスタンスを終わらせたものの
報告もそこに出ます。ホストの
`Request` は、`wasi:http` と同じく接続元のアドレスもポートもハンドラーに伝えず、`TRACE`
リクエストを受け付けません。ホストの `Headers` は、複数回設定されたレスポンスフィールドの値を
`, ` でつなぎます（`Set-Cookie` を除きます）。

**ハンドラーはエグゼキューターのスレッドで実行され、エクスチェンジは JDK と同じくハンドラーが
戻ったあとも残ります。** エグゼキューターのないサーバーでは、リクエストを受け取るスレッドが
ハンドラーを 1 つずつ実行します。このスレッドの名前は、JDK と同じ `HTTP-Dispatcher` です。
エグゼキューターを設定したサーバーでは、このスレッドがリクエストをエグゼキューターに渡し、
次のリクエストへ進みます。ハンドラーが応答しないまま戻ったエクスチェンジには、あとから
応答したスレッド（別のリクエストのハンドラーや、プログラムのスレッド）がそのまま応答し、
それまでどのスレッドも占有しません。誰も応答しないエクスチェンジは、サーバーが止まるまで
開いたままです。

**チャンクで送るボディが書き込みのたびに送られるのは、`wasmtime serve` だけです。**
`sendResponseHeaders(status, 0)` のあとの書き込みは、`wasmtime serve` では、書き込みが戻った
時点でそのチャンクが送信されています。インタプリタ、JVM、`--native` の実行ファイルは、
ボディが終わってから、長さを付けてボディ全体を送ります。長さを事前に知らせたボディは、
その長さをすべて書き込んだ時点で送られます。レスポンスの区切り方はトランスポートが決める
ため、ハンドラーが設定した `Content-Length` と `Transfer-Encoding` は捨てられます。

**失敗したリクエストにも応答があります。** ハンドラー、フィルター、オーセンティケーターが
例外を投げた場合、ヘッダーを送らずにエクスチェンジを閉じた場合、ボディが知らせた長さに
満たないまま閉じた場合は、ボディのない 500 を返します。JDK は、これらの場合に接続を閉じます。
エグゼキューターがリクエストを受け付けなかった場合も 500 です。`start()` のあとで `main` が
例外を捕捉しなかった場合、プログラムは終了します。

**`sendResponseHeaders(status, 0)` のあとでハンドラーが例外を投げると、`wasmtime serve`
ではボディが途中で切れます。** JDK と同じく、最後のチャンクを送らずに接続を閉じます。
ヘッダーがまだ送り出されていなければ、何も送りません。インタプリタ、JVM、`--native` の
実行ファイル、Worker はその時点でまだ何も送っておらず、アプリケーションが失敗したときの 500 を
返します。ボディは `Internal Server Error` で、例外を標準エラー出力（Worker ではそのログ）に
書きます。

**`stop` は、JDK と同じように遅延時間を待ちます。** 待つのは、処理中のリクエストすべてに
応答するか、遅延時間が過ぎるまでです。自分のリクエストに応答する前のハンドラーが呼んだ
`stop` は、遅延時間いっぱいまで待ちます。そのあと、まだ処理中のエクスチェンジの接続を切ります。

**リクエストは、ハンドラーが動く前にすべて読み込まれています。** ボディは読み込み済みです。
同じ名前で複数回送られたフィールドは、値を `, ` でつないだ 1 つの値になります。チャンクで
送るボディを閉じたあとに書き込むと、`stream closed` というメッセージの `IOException` に
なります。JDK は、この場合に独自のクラスの例外を投げます。

**名前解決は行いません。** `InetAddress.getByName` と、ホストを指定した `InetSocketAddress`
が受け付けるのは、数値で書いたアドレスと `localhost` です。それ以外の名前を渡すと、
`getByName` は `UnknownHostException` を投げ、`InetSocketAddress` は未解決のままになります。
未解決の `InetSocketAddress` の名前は、トランスポートがバインドします。
`InetAddress.getLocalHost` と逆引きのメソッドはありません。

## ソケット

**ソケットは、ターゲットが持つ TCP ソケットです。** `Socket` と `ServerSocket` は、
プログラム自身のソケットを通してホストのネットワークに届きます。ソケットを持つ
ターゲットは次のとおりです。

| ターゲット | ビルド | ソケット |
|---|---|---|
| インタプリタ、JVM | `rontolisp App.java`、`-o App.class` | JDK のソケット |
| WASM コンポーネント | `-o app.wasm --component`。`wasmtime run -S inherit-network=y` で実行します | `wasi:sockets`。名前の解決には `-S allow-ip-name-lookup=y` が要ります |
| ネイティブ実行ファイル | `--native` | 実行ファイル自身のソケット |
| `--no-wasi` のモジュール（Cloudflare Worker） | `-o worker.wasm --no-wasi --host-sockets` | ホストの `connect()`。`--emit-js-glue` が書くグルーを通ります |

素の Preview 1 モジュールにはネットワークがありません。プログラムが最初のソケットを
使うところで、`--component` と `--native` を示す `SocketException` を投げます。
ブラウザでも、最初のソケットで例外を投げます。`--host-sockets` を付けずにビルドした
`--no-wasi` のモジュールも同じで、例外のメッセージがこのフラグを示します。

**`--no-wasi` のモジュールのソケットは接続しかできず、リクエストと一緒に終わります。**
ホストは `cloudflare:sockets` の `connect()` で、待ち受けの機能を持ちません。そのため
`ServerSocket` と `bind` は `BindException` を投げます。接続に失敗すると、理由が何であっても
（名前が見つからない場合も含めて）、ホストのメッセージを持つ `ConnectException` になります。
ホストがローカル側のアドレスを返さない場合、`getLocalPort` は 0 を、`getLocalAddress` は
ワイルドカードアドレスを返します。生成された `worker()` の下では、開いたままのソケットは、
それを開いたリクエストと一緒に終わります。そのソケットを次に使うと
`SocketException: Socket closed: the request that opened it has ended` を投げます。
`main` の実行中はソケットを待てず、そこでインスタンスが終了します。`--host-timer` も付けて
ビルドすると、`main` の中でもソケットを待てます。`main` が作ったソケットは、最初のリクエストと
一緒に終わります。

**名前は、ソケットが接続、バインドまたは待ち受けをするときに解決します**
（`InetSocketAddress` は [HTTP サーバー](#the-http-server)を参照）。`createUnresolved` で
作ったアドレスは、JDK と同じく、そこで `UnknownHostException` になります。

**接続したソケットのオプションはホストのものです。** `TCP_NODELAY`、`SO_KEEPALIVE`、
`SO_LINGER`、`SO_OOBINLINE`、`IP_TOS`（`setTcpNoDelay`、`setKeepAlive`、`setSoLinger`、
`setOOBInline`、`setTrafficClass`、または `StandardSocketOptions` を渡す `setOption`）は、
接続の前に設定したものは接続したときに、接続の後に設定したものはすぐに、ホストのソケットに
設定します。取得するメソッドは設定した値を返します。`sendUrgentData` は、そのバイトを
ホストの緊急データとして送ります。コンポーネントのホストにあるのは、このうち
`SO_KEEPALIVE` だけです。ほかのオプションは値を保持してそのまま返し、`sendUrgentData` は
`SocketException` を投げます。`connect` の時間の上限、`setSoTimeout` の読み込みの期限、
サーバーソケットの `accept` の期限、`shutdownInput` と `shutdownOutput` は、どの
ターゲットでもホストのものです。

**値を保持して返すだけのオプションもあります。** バッファのサイズ（設定するまでは 8192 と
65536。JDK はホストの値を返します）、`SO_REUSEADDR` と `SO_REUSEPORT`、サーバーソケットの
`IP_TOS` です。サーバーソケットが受け付けた接続は、サーバーソケットの `SO_REUSEADDR` と
受信バッファのサイズを持ちます。`setTrafficClass` は下位 2 ビットを落とします。Linux の
JDK と同じ値です。`supportedOptions()` は、JDK と同じ標準のオプションを持ちます。
`jdk.net.ExtendedSocketOptions` と `IP_MULTICAST_IF` はサブセットにありません。

**時間の上限またはクローズで終わった接続は、コンポーネントのホストでは続きます。**
ホスト自身の接続が終わるまでです。`wasi:sockets` はソケットを接続に貸し出すため、
ホストのソケットはそのときに解放されます。JDK はすぐに閉じます。

**届いたバイト数は、ストリームが数えます。** `available()` は、ストリームがホストから
受け取ってまだ渡していないバイト数を返します。それがなければ、待たずに受け取れるバイト数を
最大 8 KiB まで返します。

**ホストの理由はホストの言葉です。** 拒否された接続は `ConnectException`、失敗した
バインドは `BindException`、期限を過ぎた読み込み、接続、`accept` は
`SocketTimeoutException`、見つからない名前は `UnknownHostException` になります。使用中の
アドレス、どのインターフェースも持たないアドレス、相手がリセットした接続のメッセージは JDK と
同じです。それ以外の失敗のメッセージはホストのもので、コンポーネントではエラーコードを
オペレーティングシステムの言葉にしたものです。コンポーネントは、相手がリセットした接続をストリームの終わりとして読みます。

**ソケットを待つスレッドの間も、ほかのスレッドは進みます。** スタックが 1 つの
ターゲットでも同じです（[スレッド](#threads)）。`--native` 実行ファイルとコンポーネントでは、
接続、`accept`、読み込みは、それぞれの時間の上限までスレッドの待ちになります。どのスレッドも
進めないとき、`--native` 実行ファイルは、スレッドが待つソケットと時刻、
`com.sun.net.httpserver` のサーバーの次のリクエストを、ホストで一度に待ちます。

**読み込みは、届いたものをホストの読み込み 1 回で受け取ります。** JDK と同じです。
1 MiB を 8 KiB の配列で読む時間は、`--native` 実行ファイルとコンポーネントで約 40 ミリ秒、
JVM で 0.2 秒です。JDK では数ミリ秒です。

**次のものはありません。** 名前を示して拒否します。`getChannel`、`Proxy` を取る
`Socket`、`SSLSocket` と `javax.net.ssl`、`DatagramSocket` です。

## ダイジェストと鍵

**アルゴリズムは JDK にあるダイジェストのすべてです。** `MessageDigest` は MD5、MD2、
SHA-1、SHA-224、SHA-256、SHA-384、SHA-512、SHA-512/224、SHA-512/256、SHA3-224、SHA3-256、
SHA3-384、SHA3-512、SHAKE128-256、SHAKE256-512 を、`Mac` は MD2 と SHAKE 以外のそれぞれの
HMAC を、`SecretKeyFactory` は SHA-1 と SHA-2 ファミリーの HMAC による PBKDF2 を持ちます。
名前と別名は、JDK が受け付けるものをすべて受け付けます。JDK にあってサブセットにないもの
（PBE の `Mac`、`SslMacMD5` と `SslMacSHA1`、DES、DESede、PBE のファクトリ）は、
プログラムに書かれた名前を示して拒否します。プログラムの実行時に計算した名前には、JDK と
同じ `NoSuchAlgorithmException` を投げます。

**`SecureRandom` の値は、どの名前でもホストのエントロピーから取ります。** シードは
受け取りますが、何も加えません。そのため、ある `SecureRandom` が別の `SecureRandom` と
同じ値の並びを返すことはありません。JDK の `SHA1PRNG` は、最初の値を返す前に同じシードを
与えると、同じ並びを返します。

**クラスはサブセット自身のものです。** ダイジェストのクラスはサブセットのクラスです
（JDK の `java.security.MessageDigest$Delegate$CloneableDelegate` に対して、
`rontolisp.internal.Sha256` など）。導出した鍵のクラスも同様です。`SecretKeySpec.hashCode`
と導出した鍵の `hashCode` はサブセット独自の値で、等しい鍵では同じ値になります。
ファクトリがプログラム自身の鍵を読む途中で起きた `NullPointerException` にはメッセージが
ありません。

**プロバイダは JDK のもので、サブセットのサービスを持ちます。** `Security` は、JDK が
インストールする 12 のプロバイダを、その名前、バージョン（`25`）、説明とともに返します。
`SUN` はダイジェストと `SecureRandom` を、`SunJCE` は `Mac` と PBKDF2 のファクトリを持ち、
それぞれに JDK のプロバイダと同じ名前、別名、属性を付けます。ほかのプロバイダはサービスを
持ちません。サービスのクラスはサブセットのクラスです（`rontolisp.internal.Sha256`、
`java.security.SecureRandom`、`javax.crypto.Mac`）。パラメータを取る DRBG のクラスも同様です
（`java.security.SecureRandom$Drbg`）。`getInstance` が作るものはサブセット自身の表に
従います。プログラムが `SUN` にエントリを加えたり取り除いたりすると、プロバイダの答えは
変わりますが、`getInstance` が作るものは変わりません。JDK ではそれに従います。

**プログラム自身のプロバイダ** は、加えたエントリを持ち、エントリが示すサービスを返します。
そのプロバイダのサービスを `getInstance` で求めると、JDK がクラスを見つけられないときと同じ
`NoSuchAlgorithmException` を投げます。サブセットは名前からクラスを作らないためです。

**DRBG はパラメータを保持し、値の生成と再シードをそれに照らして確かめます。** 保持するのは
強度（112、128、192、256 ビット）と予測耐性の有無です。値はどれもホストのエントロピーから
取ります。パーソナライゼーション文字列と追加入力は何も加えません。`getInstance` で
パラメータを受け付けない生成器を求めたときの例外は、JDK が自身の実装クラスを示すところで、
サブセットのクラス `java.security.SecureRandom` を示します。原因は `SHA1PRNG` でも
`IllegalArgumentException` です。JDK では `NoSuchMethodException` です。

**次のものはありません。** 名前を示して拒否します。`Security.addProvider`、
`insertProviderAt`、`removeProvider` とセキュリティプロパティ、
`Provider.Service.newInstance`、`MacSpi`、`SecretKeyFactorySpi`、`SecureRandomSpi` です。

## 暦とタイムゾーン

**既定のゾーンはホストのゾーンで、それを求めるプログラムはすべての地域を持ちます。**
`TimeZone.getDefault`、`Calendar.getInstance()`、`new GregorianCalendar()` は、Linux 上の JDK と
同じようにゾーンを読みます。`TZ` から先頭のコロンを除いたもの、なければリンク `/etc/localtime`
が指すゾーンファイルです。地域と 3 文字の ID はそのまま、カスタム ID は正規化した形、
POSIX の文字列（`JST-9`、`EST5EDT,M3.2.0,M11.1.0`）はオフセットのカスタム ID、ほかの名前は
`GMT` になります。ゾーンの名前はプログラムが動くところでホストが決めるため、そうしたプログラムは
tz データベースのすべての地域を持ちます。`.wasm` では一つの地域より 60 KB ほど大きくなります。
`.wasm` は、ホストが渡したときに `TZ` を、`/etc` を許されたときにリンクを読み、どちらもなければ
ゾーンは `GMT` です。プロパティ `user.timezone` は読みません（プログラムはシステムプロパティを
設定しません）。異なる点: ファイルのパスである `TZ` と `posix/` で始まる `TZ` は `GMT` です
（JDK はそのファイルの現在のオフセット、あるいは接頭辞のあとの地域を読みます）。

**JDK の答えに規則が見つからなかったところは拒否します。** `UnsupportedOperationException`
を投げるのは次のものです。オフセットが変わる地域に `setRawOffset` で別の標準のオフセットを
与えたあとのオフセット（オフセットが一つのゾーンは新しい値を取ります）。`long` が表せる最後の
3 年間の地域のオフセットと `SimpleTimeZone` の夏時間。1600 年より前の時点、時点と夏時間の
変わり目がともに一つの年明けから標準のオフセットの大きさと夏時間の幅の和の内にあるとき、
`WALL_TIME`、`STANDARD_TIME`、`UTC_TIME` 以外の時刻の形を持つときの `SimpleTimeZone` の夏時間。
`GregorianCalendar.roll` のうち、1583 年より前の `WEEK_OF_MONTH`、1582 年より前と、ほかの年が
番号を付ける週の日の `WEEK_OF_YEAR`、1582 年に始まる週にある 1583 年の日の `DAY_OF_WEEK`、時刻のフィールドを時計が飛ばす時刻へその変更のあとから動かすもの。
-306783378 より小さい `DAY_OF_WEEK_IN_MONTH`。1 から 7 の外にある週の最初の曜日と最初の週の
最小日数。`getTimeZone` と `SimpleTimeZone` が作るゾーンの、紀元と日付と時刻を取る
`TimeZone.getOffset`。`GregorianCalendar` の `getMinimum`、`getMaximum`、`getGreatestMinimum`、
`getLeastMaximum`。時点のフィールドを拒否するときは、それを必要とする `get` か `set` が例外を
投げます。時点だけを読む暦はそのまま動きます。

**`SimpleTimeZone` は初めて問われたゾーンとして答えます。** JDK の答えは、同じゾーン、あるいは
複製の元のゾーンが前に何を問われたかで変わることがあります（夏時間の始まりと終わりの順が年に
よって入れ替わる予定など）。

**暦とゾーンの `toString` は `Object` のものです。** ハッシュコードは独自の数値で、等しい
オブジェクトどうしで等しくなります。

**次のものはありません。** 名前を示して拒否します。寛容でない暦（`setLenient`）、
`getActualMaximum` などの限界値、`getDisplayName` と `getDisplayNames`、週の年、
`setGregorianChange`、`isSet`、`clear(int)`、ほかの `getInstance`、`toInstant`、
`toZonedDateTime`。`TimeZone.getAvailableIDs`、`setDefault`、`toZoneId`、`getDisplayName`。

## JDBC

**ドライバーはプログラムのものです。** `DriverManager` は登録されたドライバーを登録順に
持ちます。最初の検索（URL を取る `getConnection`、`getDriver`、`getDrivers`、`drivers`）の
ときに、JDK と同じく、プログラムの `META-INF/services/java.sql.Driver` が挙げるドライバーを
作ります。ドライバーはクラスの初期化で自分を登録します。作れないドライバーがあると、そこで
作るのをやめ、何も報告しません。システムプロパティ `jdbc.drivers` は読みません
（`System.getProperty` はこのキーを知りません）。サブセットにドライバーは付属しません。

**ログライターに書くトレースにはフレームがありません。** `DriverManager` にログライターが
あるあいだ、検索は JDK と同じ行を書き、`SQLException` は作られたときにトレースを書きます。
トレースは、JDK が書く行から `at` の行と `... n more` を除いたものです。

**次のものはありません。** 名前を示して拒否します。`javax.sql.RowSet` と `javax.sql.rowset` の
パッケージ、`javax.naming` の `InitialContext`、`CompositeName`、`CompoundName`、ほかのパッケージ、
`NamingException.appendRemainingComponent`（`CompositeName` を作ります）です。

## ロギング

**レコードを作った場所は、呼び出しが伝えます。** JDK はロガーを呼んだクラスとメソッドを
スタックから探します。サブセットでは、レコードを作る `Logger` のメソッドやレコードを渡す
`Logger` のメソッドを呼ぶたびに、呼び出し側のクラスとメソッドを伝えます。メソッドの名前は
コンパイラが付ける名前です（`<init>`、`<clinit>`、`lambda$main$0`）。このため、2 つの違いが
あります。メソッド参照を通した呼び出し（`list.forEach(log::info)`）は何も伝えないので、
`SimpleFormatter` は、JDK が参照を呼んだフレームを書くところにロガーの名前を書きます。
ラムダの番号は、それより前のラムダ、`super::` の参照、配列のコンストラクター
（`String[]::new`）を数えます。コンパイラはメソッドを作るほかのメソッド参照（可変長引数の
メソッド、内部クラスのコンストラクター、配列の `clone`）も数えるので、そうした参照より後の
ラムダの番号は 1 つ小さくなります。SLF4J のロガーや、そのフルーエント API のビルダーの
`log` の呼び出しも同じように伝え、SLF4J 自身のクラスは何も伝えません。そのため、レコードには
SLF4J を呼んだフレームが入ります。JDK では、`java.util.logging` へのバインディングがこの
フレームをスタックから探します（[ログ](logging.md)）。Commons Logging の `Log` の呼び出し
（`trace` から `fatal`）も同じように伝え、Commons Logging のクラスは何も伝えません。その
`Jdk14Logger` は、スタックから求めたメソッドを `Logger.logp` に渡しますが、サブセットでは
そのメソッドは呼び出しが伝えたものになります。

**設定は既定の設定で、プログラムが変更します。** ルートロガーは `INFO` で、標準エラーに書く
`ConsoleHandler` を持ちます。この `ConsoleHandler` は、ルートのハンドラーが最初に求められた
ときに作られます。設定ファイルは読みません。どのターゲットにもシステムプロパティ
`java.util.logging.config.file` がないためです。`LogManager.getProperty` は既定の設定の
`handlers`、`.level`、`ConsoleHandler` の `level` と `formatter` を返し、
`readConfiguration()` はその設定に戻します。`SimpleFormatter` は既定の書式で書きます。
`--wasi-logging` を付けたビルドでは、ルートのハンドラーは JDK にないハンドラーになり、
レコードをホストのログへ渡します（[ホストのログ](logging.md#the-hosts-log)）。
`getProperty("handlers")` はこのハンドラーの名前を返します。

**ロガーは保持されます。** JDK は、どこからも参照されないロガーを、設定したレベルごと回収する
ことがあります。サブセットでは、名前を持つロガーはプログラムの終わりまで残ります。
`LogRecord.getLongThreadID` はスレッドの `threadId()` で、rontolisp 独自の番号です
（[スレッド](#threads)）。

**次のものはありません。** 名前を示して拒否します。リソースバンドル
（`Logger.getLogger(String, String)`、`logrb`、`setResourceBundle`。バンドル名を渡した
`Logger` のサブクラスは `UnsupportedOperationException` を投げます）、
`readConfiguration(InputStream)`、`updateConfiguration`、`FileHandler`、`SocketHandler`、
`MemoryHandler`、`XMLFormatter`、`LoggingPermission` です。書式の型を持つメッセージの
パラメータ（`{0,number}`）は `MessageFormat` が書きますが、`MessageFormat` にはまだ型が
ありません。`UnsupportedOperationException` を投げ、ハンドラーはそれを `ErrorManager` で
報告します。

**Commons Logging が `Jdk14Logger` を見つけるのは、到達可能性メタデータが宣言している
場合だけです**（[ログ](logging.md#commons-logging)）。宣言がなく、SLF4J もなければ、
`LogFactory.getLog` は `LogConfigurationException: No suitable Log implementation` を
投げます。JDK はこのクラスをクラスパスから見つけます。

## 拒否される構文

どれも、構文の名前と位置を示すエラーになります。ソースパスのライブラリの中では、サブセットに
無い JDK のクラスやメンバーを参照する文はエラーではなく警告になり、実行された時点で例外を
投げます（[サードパーティのライブラリを使う](index.md#using-a-third-party-library)）。

- リフレクション。注釈付きの型（`getAnnotatedType`、`getAnnotatedReturnType`、
  `getAnnotatedSuperclass` など）と型変数のアノテーション。`java.lang.invoke` のうち
  [JDK のクラス](classes.md)にないもの（`VarHandle`、`MethodHandles` のコンビネーター、
  `findGetter` などフィールドのハンドル、`Lookup.in`）。
- `long`、`float`、`double`、`boolean` に対する `switch`。修飾した `super` を通すメソッド参照と
  フィールド（`Interface.super::m`、`Outer.super.x`）。
- `String`、`StringBuilder`、ボックスを継承するクラス。
- `java.util.concurrent` のうち、[使える JDK のクラス](classes.md)の表にないもの。優先度順または遅延順の
  キューです。`Date` を取る `Timer` の `schedule`、スレッドグループも拒否されます。
- [使える JDK のクラス](classes.md)の表にない JDK のクラスとメンバー。リフレクション、
  [ファイル](#files)と[暦とタイムゾーン](#calendars-and-time-zones)に挙げたものなどです。
  `java.net` では、`URI`、`URL`、アドレス、[ソケット](#sockets)、
  [HTTP クライアント](#the-http-client)以外のすべてが該当します。
  `HttpURLConnection`、`DatagramSocket`、[ソケット](#sockets)に挙げたものです。
