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

**static フィールドは `main` の前に、ファイルに書かれた順でクラスごとに設定されます。**
言語仕様では、クラスは最初に使われたときに初期化されます。この違いが結果に現れないように、
static フィールドの初期化子から読めるのは、定数と、同じクラスでそれより前に宣言された
static フィールドだけです。プログラム自身のメソッドやコンストラクタは呼べません。それ以外の
初期化子は拒否します。プリミティブ型か `String` の `static final` フィールドで初期化子が
定数式のものは、どこから読んでも定数です。

**実行時のエラーは言語仕様の例外ではありません。** 0 による整数の除算は、メッセージが
`/ by zero` の `ArithmeticException` です。`null` に対する呼び出し、配列の範囲外の添字、
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

**コンパイラが報告するエラーの一部は報告されません。** 代入前のローカル変数の読み出し、
到達できないコード、`return` の不足、宣言されていないチェック例外、アクセス修飾子、
`final` 変数への代入は検査しません。そのようなプログラムは実行されます。

**`hashCode` も `toString` も宣言していないオブジェクトの `hashCode` と `toString`** は、
rontolisp が割り当てる識別番号と、`Name@` にその番号の 16 進表記を続けた文字列です。

## 拒否される構文

どれも、構文の名前と位置を示すエラーになります。

- `switch`、`enum`、`record`、ネストしたクラス、ローカルクラス、匿名クラス、ジェネリックな
  クラスとメソッド、sealed 型、初期化ブロック、インターフェースの `default` メソッドと
  `static` メソッド、インターフェースの定数、可変長引数、static import。
- パターン（束縛を伴う `instanceof`）、メソッド参照、無名変数 `_`、テキストブロック、
  ラベル付きの文、`try`-with-resources、マルチキャッチ、`finally` ブロックから抜ける
  `return`、`break`、`continue`、`this(..)` や `super(..)` より前の文。
- スーパークラスのフィールドを隠すフィールド。`float`、`byte`、`short`。長さを指定して作る多次元配列（`new int[2][3]`。`new int[2][]` と
  初期化子は読めます）。
- 2 つの文字列、または 2 つのボックスの `==` と `!=`。これらは参照を比較します。`equals` で
  比較するか、片方をアンボクシングしてください。
- プログラム内のラムダの一部だけが実装しているインターフェースに対する `instanceof` と
  キャスト。ラムダは、どのインターフェースのために作られたかを記録していません。
- `Runnable` 以外のライブラリのインターフェース（`java.util.function`）に対するラムダ。
- スレッド、`synchronized`、`volatile`。
- [概要](index.md)の表にないライブラリのクラスとメンバー、および `HashMap` の文字列表現。
