# Java のプログラムをテストする

プログラムのテストが JDK で通っても、rontolisp がコンパイルしたプログラムは JDK のものでは
ありません。[サブセット](index.md)は言語を自前で処理し、クラスも自前のものを持つので、
surefire で通ったスイートは、コンパイルしたプログラムが正しく動くことの保証になりません。
`rontolisp test` は同じテストソースを、rontolisp が作るプログラムに対して実行します。

```console
$ cat src/test/java/shop/CartTest.java
package shop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CartTest {

    @Test
    void anEmptyCartCostsNothing() {
        assertThat(new Cart().total()).isZero();
    }

    @ParameterizedTest
    @CsvSource({ "tea, 2, 900", "cup, 1, 1200" })
    void aLineCostsItsPriceTimesItsCount(String item, int count, int total) {
        assertThat(new Cart().add(item, count).total()).isEqualTo(total);
    }

    @Test
    void anUnknownItemIsRefused() {
        assertThatThrownBy(() -> new Cart().add("spoon", 1)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("spoon");
    }

}
$ rontolisp test --java-source-path src/main/java \
    --java-source-dep org.junit.jupiter:junit-jupiter-params:6.0.3 src/test/java/shop/CartTest.java
Running shop.CartTest
Tests run: 4, Failures: 1, Errors: 0, Skipped: 0 <<< FAILURE! -- in shop.CartTest
shop.CartTest.aLineCostsItsPriceTimesItsCount[2] <<< FAILURE!

expected: 1200
 but was: 1000

Results:

Tests run: 4, Failures: 1, Errors: 0, Skipped: 0
$ echo $?
1
```

ターゲットはテストファイル 1 つ、または 1 つのソースツリーの複数のファイルで、1 つの
プログラムとして実行します。最初のファイルのクラスが先で、次のファイルのクラスが続きます。
コンパイルしたプログラムには走査するクラスファイルがないので、テストはファイルをコンパイル
するときに見つけます。対象は、各ファイルのクラスのうちテストを持つものすべてと、その中の
`@Nested` クラスです。2 つ目以降のファイルは、最初のファイルのツリーの、パッケージとクラス名が
示すパスに置きます。

```bash
rontolisp test --java-source-path src/main/java \
    --java-source-dep org.junit.jupiter:junit-jupiter-params:6.0.3 \
    src/test/java/shop/CartTest.java src/test/java/shop/stock/StockTest.java
```

ディレクトリを渡すと、その下の `.java` ファイルをパスの順に渡したのと同じになります。
`rontolisp test ... src/test/java` はツリーのテストをすべて実行します。どのクラスがテストを
持つかはファイルと同じ方法で調べます。自身にテストのアノテーションがないクラスも、テストを
継承していることがあるためです。

テスト対象のコードは
[ソースパス](index.md#using-a-third-party-library) (`--java-source-path`、
`--java-source-dep`) から読み、テストと一緒にコンパイルします。そのためテストは、javac のもとと
同じように、自分のパッケージのパッケージプライベートなメンバーを呼べます。

JUnit Jupiter は、プロジェクトの依存であるのと同じくプログラムの依存であり、ほかのライブラリと
同じようにソースからコンパイルします。`--java-source-dep
org.junit.jupiter:junit-jupiter-params:VERSION` にプロジェクトがテストに使うバージョンを書くと、
パラメータ化テスト、API、それらの依存がそろいます (パラメータ化テストのないファイルなら
`junit-jupiter-api` で足ります)。そのライセンス (EPL-2.0) に従うのは、JDK で実行するときと同じく
プログラムの側です ([コンパイルしたプログラムのライセンス](../compiling/licenses.md))。
JUnit Jupiter がないと、そのことを伝えて止まります。

```console
$ rontolisp test --java-source-path src/main/java src/test/java/shop/CartTest.java
error: src/test/java/shop/CartTest.java:12:5: the class org.junit.jupiter.api.Test is not on the source path: JUnit Jupiter is a dependency of the program, compiled from its sources (--java-source-dep org.junit.jupiter:junit-jupiter-params:VERSION)
```

AssertJ は、ソース jar がソースパスにあっても（Maven プロジェクトではテスト依存がそこに
置かれます）ソースパスからは読みません。サブセットがその一部を、同じ名前で自前で持っています。

終了ステータスは、全テストが通れば **0**、1 つでも失敗したとき・コンパイルが拒否されたとき・
テストが 1 つも実行されなかったときは **1**、コマンドラインが誤っていれば **2** です。
surefire の数え方と同じく、`AssertionError` を投げたテストは失敗、それ以外の例外はエラーです。
パラメータ化テストは 1 回の実行が 1 つのテストで、実行の中での順番が名前に付きます。
アサンプションが成り立たないテスト (`assumeTrue`、`abort`) はスキップです。`@BeforeAll` で
成り立たなければそのクラスのテストすべてをスキップし、surefire と同じく、テストと `@Nested`
クラスをそれぞれ 1 つと数えます。

## 4 つのバックエンドで

`-o` がなければテストはインタプリタで実行されます。`-o` を付けると実行そのものがコンパイル
され、成果物は同じレポートと同じ終了ステータスを持ちます。

```bash
T="rontolisp test --java-source-path src/main/java
   --java-source-dep org.junit.jupiter:junit-jupiter-params:6.0.3
   src/test/java/shop/CartTest.java"
$T                                                          # interpreter
$T -o CartTests.class && java CartTests                     # JVM
$T -o cart-tests.wasm && wasmtime run cart-tests.wasm       # WASM preview 1
$T -o cart-tests-comp.wasm --component && \
  wasmtime run cart-tests-comp.wasm                         # WASM component
```

`@TempDir` のディレクトリは `/tmp` に作ります。`.wasm` から使うには `/tmp` をプリオープン
します (`wasmtime run --dir /tmp cart-tests.wasm`)。

## Maven プロジェクト

[Maven プラグイン](../compiling/maven-plugin.md#junit-jupiter-tests-srctestjava)の `test`
ゴールは、`test` フェーズで surefire の後に、プロジェクトのテストをこの方法で
`<backends>` が挙げるバックエンドで実行し、失敗したテストの名前を示してビルドを失敗させます。

## JUnit Jupiter

| アノテーション | 実行されるもの |
| --- | --- |
| `@Test` | `@TempDir` と `TestInfo` 以外のパラメータのないメソッド |
| `@RepeatedTest` | メソッドを `value` 回実行します。各回には `RepetitionInfo` を与え、`name` (`{displayName}`、`{currentRepetition}`、`{totalRepetitions}`) で名前を付けます。失敗が `failureThreshold` 回に達すると残りはスキップします。JUnit と同じく、スキップする回にもインスタンスは作ります。`value` が正でない、しきい値が範囲外、名前が空白のときはテストのエラーです |
| `@ParameterizedTest` | ソースが与える行ごとに 1 回ずつメソッドを実行し、そのあと `AutoCloseable` である引数を最後のものから閉じます (`autoCloseArguments = false` なら閉じません)。ソースは `@ValueSource` (`classes` 以外の全要素)、`@CsvSource` (`value`、`textBlock`、`delimiter`、`delimiterString`、`quoteCharacter`、`emptyValue`、`nullValues`、`ignoreLeadingAndTrailingWhitespace`)、`@MethodSource` (文字列リテラルで名前を書いたそのクラスの static メソッドか、`@TestInstance(PER_CLASS)` なら共有インスタンスのメソッド、または名前を省いてテストと同名のもの。戻り値は `Arguments`・配列・単一の引数の、`Stream`、`Iterable`、配列、`IntStream`、`LongStream`、`DoubleStream`)、`@EnumSource` (`value`、`names`、`mode`)、`@NullSource`、`@EmptySource`、`@NullAndEmptySource` です。文字列は、プリミティブ型・そのボックス・enum のパラメータに変換され、数値は拡大変換されます |
| `@BeforeEach`、`@AfterEach` | 各テストの前後に、そのインスタンスで実行します。テストの `TestInfo` を与え、前後で実行するテストがすべて繰り返しテストなら `RepetitionInfo` も与えます。インタフェースと上位クラスのものはテストの前では先に、後では最後に実行し、`@Nested` クラスを囲むクラスのものは内側のクラスのものの外側で実行します |
| `@BeforeAll`、`@AfterAll` | static メソッド (`@TestInstance(PER_CLASS)` なら任意のメソッド) で、クラスの `TestInfo` を与え、そのクラスとその `@Nested` クラスのテスト全体の前後に実行します。例外を投げるとクラスのエラーになり、そのクラスのテストは実行されません |
| `@TestInstance` | `PER_CLASS` なら、クラスのテスト全体で 1 つのインスタンスを使います。`@BeforeAll` の前に作り、作れなければそのクラスは何も実行しません |
| `@TestMethodOrder`、`@Order` | クラスのテストを `MethodOrderer.OrderAnnotation` (`@Order` の順。付いていないテストは `Order.DEFAULT`)、`MethodName`、`DisplayName` (名前が同じならパラメータの型の順) の順序で実行します |
| `@Tag`、`@Tags` | `TestInfo.getTags()` が返すタグ。テストのもの、次にクラスのもの (上位クラス、各インタフェース、自身の順)、次に囲むクラスのもので、前後の空白を除き、不正なタグは除きます。タグでテストを選ぶ実行はありません |
| `@Nested` | 内部クラスのテスト。テストごとに、囲むクラスのインスタンスの中に作ったインスタンスで実行します |
| `@Disabled` | テストをスキップとして数えます。クラスなら、そのテストと `@Nested` クラスをそれぞれ数えます |
| `@TempDir` | 型が `Path` か `File` のフィールドまたはパラメータ。それぞれに専用のディレクトリを `/tmp` に作ります。static フィールドのものはそのクラスのテスト全体の前後、ほかは各テストの前後で、終わると中身ごと削除します |
| `@DisplayName` | テストに付けたものは、失敗したときにテスト名の後ろに書かれます。テストやクラスに付けたものは `TestInfo` の表示名になります |

同じソースを複数回書いたときは、JUnit と同じ順で行を与えます。同じ種類のソースをまとめて、
そのうち最初のものを書いた位置で使います。`Named` の引数は、その中身を渡します。

アサーションとアサンプションをはじめ JUnit Jupiter の API は、ソースからコンパイルした JUnit
自身のものです。そのため、失敗したアサーションはそのバージョンの JUnit と同じメッセージを
出します。JUnit Jupiter 6.0.3 のうち、テストが呼んだ箇所でサブセットが拒否するのは
`assertTimeoutPreemptively` (スレッドのスタックを読みます) です。

クラスは、プログラムの上位クラスと実装するインタフェースのテストを継承します (インタフェースの
default メソッドと、static の `@BeforeAll`・`@AfterAll`)。アノテーションなしでオーバーライド
したメソッドはテストではありません。`@TestInstance` と `@TestMethodOrder` は、クラス、その
インタフェース、上位クラスの順に探し、`@Nested` クラスは囲むクラスの順序付けを引き継ぎます。
順序付けがなければクラスのテストは宣言順に実行されます。JUnit は独自の順序で実行します。

## AssertJ のうちあるもの

`org.assertj.core.api.Assertions` には、下の表の種類の `assertThat`、`assertThatThrownBy`、
`assertThatCode`、`assertThatExceptionOfType` (と `assertThatIllegalArgumentException`、
`assertThatIllegalStateException`、`assertThatNullPointerException`、
`assertThatIOException`)、`assertThatNoException`、`catchThrowable`、`fail`、`entry` が
あります。

| `assertThat` の対象 | 検査 |
| --- | --- |
| 任意の値 | `isEqualTo` (配列どうしは要素で比較)、`isNotEqualTo`、`isNull`、`isNotNull`、`isSameAs`、`isNotSameAs`、`isInstanceOf`、`isNotInstanceOf`、`isExactlyInstanceOf`、`isInstanceOfSatisfying`、`isIn`、`isNotIn`、`hasToString`、`doesNotHaveToString`、`matches`、`satisfies`、および `as`、`describedAs`、`withFailMessage`、`overridingErrorMessage` |
| オブジェクト | 関数を取る `extracting` |
| `String` | `isEmpty`、`isNotEmpty`、`isNullOrEmpty`、`isBlank`、`isNotBlank`、`hasSize` とその 4 つの比較、`contains`、`doesNotContain`、`containsIgnoringCase`、`containsSubsequence`、`startsWith`、`doesNotStartWith`、`endsWith`、`doesNotEndWith`、`isEqualToIgnoringCase`、`matches`、`doesNotMatch` |
| `int`、`long`、`float`、`double` とそのボックス | `isZero`、`isNotZero`、`isOne`、`isPositive`、`isNegative`、`isNotNegative`、`isNotPositive`、`isGreaterThan`、`isGreaterThanOrEqualTo`、`isLessThan`、`isLessThanOrEqualTo`、`isBetween`、`isStrictlyBetween`、`isEqualByComparingTo`。`double` には `isNaN`、`isNotNaN` |
| `char`、`boolean` | `isGreaterThan`、`isLessThan`。`isTrue`、`isFalse` |
| `Iterable`、`List` | `isEmpty`、`isNotEmpty`、`isNullOrEmpty`、`hasSize` とその 4 つの比較、`contains`、`containsAll`、`doesNotContain`、`containsExactly`、`containsExactlyElementsOf`、`containsExactlyInAnyOrder`、`containsExactlyInAnyOrderElementsOf`、`containsOnly`、`containsSequence`、`containsSubsequence`、`anyMatch`、`allMatch`、`noneMatch`、`allSatisfy`、`anySatisfy`、`extracting`、`map`、述語を取る `filteredOn`、`first`、`last`、`element`、`singleElement` |
| オブジェクトの配列と、`byte`、`int`、`long`、`char`、`double` の配列 | `isEmpty`、`isNotEmpty`、`isNullOrEmpty`、`hasSize`、`contains`、`doesNotContain`、`containsExactly`、`containsExactlyInAnyOrder`。オブジェクトの配列には `containsOnly`、プリミティブ型の配列には `startsWith` |
| `Map` | `isEmpty`、`isNotEmpty`、`isNullOrEmpty`、`hasSize`、`containsKey`、`containsKeys`、`doesNotContainKey`、`doesNotContainKeys`、`containsValue`、`doesNotContainValue`、`containsEntry`、`doesNotContainEntry`、`contains`、`containsAllEntriesOf`、`containsOnly`、`containsOnlyKeys` |
| `Optional` | `isPresent`、`isNotEmpty`、`isEmpty`、`isNotPresent`、`contains`、`hasValue`、`hasValueSatisfying`、`get` |
| `Throwable` | `hasMessage`、`hasMessageContaining`、`hasMessageNotContaining`、`hasMessageStartingWith`、`hasMessageEndingWith`、`hasMessageMatching`、`hasNoCause`、`hasCauseInstanceOf`、`hasRootCauseInstanceOf`、`hasRootCauseMessage`、`cause`、`rootCause`、`doesNotThrowAnyException`。`isThrownBy` の後では、同じ検査を `withMessage` .. `withRootCauseInstanceOf`、`havingCause`、`havingRootCause` の名前で使えます |

失敗した検査は AssertJ と同じ文言を出し、JUnit と併用したときに AssertJ が投げるのと同じ
クラスを投げます (2 つの値を比べる検査は `AssertionFailedError`、それ以外は
`AssertionError`)。AssertJ がスタックトレースのフレームや JDK 内部のクラス名を足す箇所では、
メッセージにそれらが入りません。

## ないもの

JUnit のアノテーションのうちテストの実行が扱わないもの、扱うアノテーションの要素のうち読まない
もの、AssertJ のクラスや検査のうちサブセットにないものは、ファイルをコンパイルするときに名前を
挙げて拒否します。黙って飛ばすことはありません。

```console
$ rontolisp test --java-source-dep org.junit.jupiter:junit-jupiter-api:6.0.3 \
    src/test/java/shop/SlowTest.java
error: src/test/java/shop/SlowTest.java:13:5: the annotation org.junit.jupiter.api.Timeout is not supported by the experimental Java subset yet
```

ないものの例は次のとおりです。`@TestFactory`、`@Timeout`、`@TestClassOrder`、
`MethodOrderer.Random` と独自の順序付け、エクステンション、アノテーション型に付けた JUnit の
アノテーション (合成アノテーション)、上の表にない要素 (`@ValueSource` の
`classes`、`@EnumSource` の `from` と `to`、`@TempDir` の `cleanup` と `factory`)、`@TempDir`
でも `TestInfo` でもない `@Test` メソッドのパラメータ、型が JUnit のものであるパラメータ化
テストのパラメータ (`ArgumentsAccessor`、引数を表示名に含む `TestInfo`)、繰り返しでないテストの
前後で与える `RepetitionInfo`。AssertJ では、リフレクションでクラスのフィールドを読む検査
(`usingRecursiveComparison`、`hasFieldOrPropertyWithValue`、名前を取る `extracting`)、ソフト
アサーション、日付・ファイル・フューチャーのアサーション。テストクラスのコンストラクタは 1 つで、
パラメータは `TestInfo` と `@TempDir` のディレクトリにできます。テストクラスの中の `static` クラスは、ファイル直下のクラスとして
書いたときだけ実行されます。

`rontolisp format` は `.java` ファイルを拒否します。このコマンドが整形するのは S 式です。
