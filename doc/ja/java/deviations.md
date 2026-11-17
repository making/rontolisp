# 言語との差異

サブセットであることは設計上の選択です。サブセットが受け付けるプログラムの動作が言語仕様と
異なる箇所はここに挙げたとおりで、どのバックエンドでも同じです。

## 動作が異なる点

**文字列はコードポイントの列です。** `length`、`charAt`、`substring` は Unicode の
コードポイントを数えます。言語仕様は UTF-16 のコード単位を数えるので、基本多言語面の外の
文字を含む文字列では結果が変わります。`hashCode` は仕様どおりの値です。

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

**同じ数をボクシングした `Long` と `Integer` は同じ値です。** ボックスは数そのものなので、
同じ数をボクシングした `long` と `int` の `equals` は真になり、ボクシングした `long` の
`getClass()` は `java.lang.Integer` を返します。

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

**実行時のエラーは言語仕様の例外ではありません。** 0 による整数の除算は、メッセージが
`/ by zero` の `ArithmeticException` です。`null` に対する呼び出し、`null` に対する `switch`、配列の範囲外の添字、
`Integer.parseInt` の失敗などは、`RuntimeException`、`Exception`、`Throwable` の `catch` で
捕捉できるエラーになりますが、クラスとメッセージは言語仕様のものと異なります。`null` に対する
呼び出しやフィールドの読み出しは、検出されない場合があります。失敗したキャストは、メッセージの
ない `ClassCastException` を投げます。

**捕捉されなかった例外は rontolisp の形式で報告されます。** 標準エラー出力に
`Unhandled condition:` と例外の `toString()` を書き、0 でない終了コードで終了します。
スタックトレースはありません。

```console
$ rontolisp Uncaught.java
Unhandled condition: java.lang.IllegalStateException: insufficient funds: 30
  at Uncaught.java:4 in Uncaught.check(int,int)
```

**コンパイラが報告するエラーの一部は報告されません。** そのようなプログラムは実行されます。
宣言されていないチェック例外は報告しません。代入前のローカル変数の読み出しは、`if`、`while`、
`for` の分岐をたどって、どの経路でも代入されていない場合に報告します。`switch`、`try`、`do`
ループ、条件式の中にある代入は、行われたものとして扱います。到達できない文は、`return`、
`throw`、`break`、`continue`、終わらないループの直後にある場合に報告します。`return` の不足は、
メソッドの最後の文が必ず正常に完了する場合に報告します。コンパイラがこれら以外の場合に
見つけるエラーは報告しません。初期値なしで宣言して 2 回代入したローカル変数を、ラムダや
ローカルクラスから読んでも拒否しません。アクセス修飾子のうち検査するのは `private` です。
プログラムは 1 つのファイルなので、ほかの修飾子によってアクセスが制限されることはありません。

**レコードの `hashCode`** は、コンポーネントを宣言順に `31 * h + hash` で畳み込んだ値です。
言語仕様はこの値を定めていません。`double` のコンポーネントを持つレコードには、まだ `hashCode`
がありません。

**`hashCode` も `toString` も宣言していないオブジェクトの `hashCode` と `toString`** は、
rontolisp が割り当てる識別番号と、`Name@` にその番号の 16 進表記を続けた文字列です。

## 拒否される構文

どれも、構文の名前と位置を示すエラーになります。

- ジェネリックなクラス、レコード、メソッド。static import、アノテーション型、`assert`。
- メソッド参照、クラスリテラル、`try`-with-resources、マルチキャッチ、`finally` ブロックから
  抜ける `return`、`break`、`continue`、`yield`。
- `long`、`double`、`boolean` に対する `switch`。コロン形式の `case` が次の `case` に
  フォールスルーする `switch` 式。`Interface.super.m()`。内部クラスを、その外側のクラスの外で
  継承するクラス。`outer.new` で作る匿名クラス。
- スーパークラスのフィールドを隠すフィールド。`float`、`byte`、`short`。
- 2 つの文字列、または 2 つのボックスの `==` と `!=`。これらは参照を比較します。`equals` で
  比較するか、片方をアンボクシングしてください。
- プログラム内のラムダの一部だけが実装しているインターフェースに対する `instanceof` と
  キャスト。ラムダは、どのインターフェースのために作られたかを記録していません。
- `Runnable` 以外のライブラリのインターフェース（`java.util.function`）に対するラムダ。
- スレッド、`synchronized`、`volatile`。
- [概要](index.md)の表にないライブラリのクラスとメンバー、および `HashMap` の文字列表現。
