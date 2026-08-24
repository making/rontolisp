# JVM ライブラリのエクスポート（jvm-export / --no-main）

コンパイルされた `.class` は通常は*コマンド*です: `java Prog` がトップレベルを
実行して終了します。このガイドはもう 1 つの形 — Java コードが関数を直接呼び出す
*ライブラリ*クラス — について説明します。これを実現するのは 2 つの部品です。

- [`rontolisp:jvm-export`](../reference/functions/rontolisp-jvm-export.md) は
  `defun` に対して、型付きで Java から呼び出し可能な `public static` メソッドを
  宣言します。
- `--no-main` は `main` エントリポイントを取り除き、クラスをエクスポートだけの
  ものにします。

これは WASM 側の
[`rontolisp:wasm-export`](../reference/functions/rontolisp-wasm-export.md) と
`--no-wasi` リアクタモードの JVM 版の双子であり、同じ問題を解きます:
コンパイルされた `defun` の型なしメソッド
(`public static Object NORM2(Object)`) は、Java の呼び出し側が安全に構築
できない内部表現を受け取り、返します — 文字列引数をそのまま渡すと、内部の
文字列表現が素の Java `String` ではないため、黙って誤読さえされます。型付き
ラッパーが安全な境界です。

## ライブラリを最初から最後まで

`kernels.lisp`:

```lisp
(defvar *scale* 2.0)

(defun scaled-sum (a b)
  (* *scale* (+ a b)))

(defun greet (name)
  (concatenate 'string "hello, " name))

(rontolisp:jvm-export 'scaled-sum :params '(:float :float) :returns :float)
(rontolisp:jvm-export 'greet :params '(:string) :returns :string)
```

コンパイルします。`-o` パスのディレクトリはクラスの Java パッケージになり、
ディレクトリは自動的に作成されます。

```bash
rontolisp kernels.lisp -o com/example/Kernels.class --no-main
```

クラスはディレクティブが宣言したとおりの API を持ちます。

```java
package com.example;

public class Kernels {
    public static double scaledSum(double a, double b);
    public static String greet(String name);
}
```

Java の呼び出し側は他のクラスと同じように使います。

```java
import com.example.Kernels;

public class App {
    public static void main(String[] args) {
        System.out.println(Kernels.scaledSum(2.5, 3.5)); // 12.0
        System.out.println(Kernels.greet("ron"));        // hello, ron
    }
}
```

```bash
javac -cp . App.java && java -cp . App
```

メソッド名のデフォルトは Lisp 名の lower-camel-case 変換 (`scaled-sum` は
`scaledSum`) で、`:as "name"` で別名を選べます。型指定子と Java 型の対応表、
および境界の「正確に運ぶか、さもなければスローする」変換規則は
[リファレンスページ](../reference/functions/rontolisp-jvm-export.md) に
あります。

## トップレベルはクラス初期化時に実行される

上の `(defvar *scale* 2.0)` は最初の `scaledSum` 呼び出しが届く前に実行されて
いなければならず、`main` がそれを実行することはありません。そのため
エクスポートを持つクラスは、トップレベルのフォームをクラスイニシャライザで —
JVM がクラスに最初に触れたとき一度だけ — 実行します。これは
インスタンス化時にトップレベルを実行する `--no-wasi` リアクタと同じ設計で、
同じ 2 つの鋭い角があります: シグナルするトップレベルフォームは
`ExceptionInInitializerError` として現れ (クラスは呼び出し側の JVM の寿命の間
汚染されたままになります)、トップレベルの `(uiop:quit ...)` は呼び出し側の
JVM を終了させます。ライブラリのトップレベルは定義と初期化にとどめてください。

`--no-main` はディレクティブとは直交します: フラグなしではクラスは `main`
**と**エクスポートの両方を持ちます — ライブラリでもある CLI ツールです。
その場合 `main` はクラス初期化をトリガーする以外何もしないので、プログラムは
やはり一度だけ実行されます。フラグありでは最低 1 つの `jvm-export` が必要です:
それがなければ `main` が唯一の
[ツリーシェイカー](../compiling/jvm.md#optimize-dead-code-elimination)ルート
であり、`main` のないエクスポートなしのクラスは何もないところまで
シェイクされてしまいます。エクスポートは追加のシェイカールートであり、これが
ライブラリが `--optimize=off` でランタイム全体を抱え込む代わりにデフォルトの
`--optimize` サイズを保てる理由です。

## パック済み float 配列

`linalg:` と `vec:` の値は**パック済み float 配列**であり、そのまま境界を
渡ります — `:float-vector` (ランク 1) と `:float-matrix` (ランク 2) で、
どちらも 1 つの Java クラス `am.ik.rontolisp.runtime.RontoFloatArray` が
運びます。

```lisp
(defun norm2 (x)
  (sqrt (vec:dot x x)))

(defun axpy (a x y)
  (vec:add (vec:scale x a) y))

(rontolisp:jvm-export 'norm2 :params '(:float-vector) :returns :float)
(rontolisp:jvm-export 'axpy :params '(:float :float-vector :float-vector) :returns :float-vector)
```

```java
import am.ik.rontolisp.runtime.RontoFloatArray;
import com.example.Kernels;

RontoFloatArray x = RontoFloatArray.of(new double[] { 3.0, 4.0 });   // copies, once
double n = Kernels.norm2(x);                                          // 5.0
RontoFloatArray y = Kernels.axpy(2.0, x, RontoFloatArray.of(new double[] { 1.0, 1.0 }));
double[] out = y.toArray();                                           // copies out, once
```

**なぜ `double[]` ではなくハンドルなのか。** パック済み float 配列は次元
ヘッダを埋め込んだ素の `double[]` (または `float[]`) なので、ただの Java
配列はそれではありません — `new double[]{3, 4}` を渡してもコンパイルは通り、
誤った数値が返ります。呼び出しごとに変換すれば安全ですが、その費用は与える
カーネルのおよそ 10 倍で、3 倍の勝ちが 3 倍の負けに変わります。

| | ms/call | plain Java 比 |
| --- | --- | --- |
| plain Java ループ、C2 が自動ベクトル化 | 0.89 | 1.00x |
| パック済み配列へのカーネル (下限) | 0.29 | 3.06x |
| **ハンドル越しのカーネル** | **0.29** | **3.12x** |
| 呼び出しごとにコピーするファサード越し | 2.58 | 0.35x |

ハンドルはパック済み表現を呼び出しをまたいで保持します: `of(...)` が一度
コピーし、`toArray()` が一度コピーして返し、その間の呼び出しは何もコピー
しません。ベンチマークは
[`examples/jvm/bench/`](https://github.com/making/rontolisp/tree/main/examples/jvm/bench)
です。

**ハンドルは Lisp 側の配列をエイリアスします。** カーネルが*返した*ハンドルは
Lisp 側が保持しているまさにその配列です: ハンドル経由の `set(i, v)` は同じ
配列を閉じ込めた Lisp のクロージャから見え、逆に Lisp 側の書き込みは
`get(i)` から見えます。防御的コピーは一切しません — そのコピーこそ表の最終行
です。`of(...)` と `toArray()` がコピーの起きる 2 箇所であり、それはあなたが
コピーを頼んだ 2 箇所です。これは destination-passing も成立させます:
`RontoFloatArray.zeros(...)` が作ったバッファに `vec:...-into` の
エクスポートが書き込むので、Java 側のループは反復ごとに何も確保しません。

**`--gpu` では、結果は読むまでデバイスに残ります。** `--gpu` カーネルが
返したハンドルは境界で持ち帰られないので、Java 側の連鎖
`h = Kernels.step(w, h)` は途中の値をすべてデバイスに残し、最後の読み出し
だけが 1 つを持ち帰ります。常駐した 2048x2048 行列に対する 200 回連鎖の
GEMV での計測では反復あたり 0.070 ms — Lisp から一度も出ないループと同じ —
であり、アップロードは呼び出しごとではなく実行全体で 1 回です。ここから
言えるのは、費用があるのは*読み出し*だということです: 新しいデバイス結果に
対する最初の `get(i)` または `toArray()` がダウンロードを払い、以降は
払いません。だから結果は要素ごとではなく一度に読んでください。

要素幅はどちらも同じ指定子を通り (`of(double[])` と `of(float[])`、どちらかは
`width()` が答えます)、ランクはヘッダから来るので、行列は 2 つ目の型ではなく
ランク 2 の `dims()` を持つ同じクラスです。指定子が宣言していないランクは
境界でスローされます。

## Maven プロジェクト: `src/main/lisp`

カーネルとそれを呼ぶ Java が同じプロジェクトにあるなら、Lisp をアーティファクトにす
る必要はありません。単なるもう 1 つのソースセットであり、`target/classes` のパッケー
ジング方法は Maven がすでに知っています。`<plugin>` ブロック 1 つが設定のすべてです:

```xml
<plugin>
    <groupId>am.ik.rontolisp</groupId>
    <artifactId>rontolisp-maven-plugin</artifactId>
    <version>VERSION</version>
    <executions>
        <execution>
            <goals><goal>compile</goal></goals>
            <configuration>
                <simd>true</simd>
            </configuration>
        </execution>
    </executions>
</plugin>
```

```
pom.xml
src/main/lisp/com/example/Kernels.lisp   <- the kernels, with their jvm-export declarations
src/main/java/app/App.java               <- Kernels.scaledSum(...), Kernels.norm2(...)
```

```bash
mvn package     # one jar, Lisp classes and Java classes together
```

**`src/main/lisp` 以下のパスがクラス名になります**。`.java` ファイルのパスと同じで、
`src/main/lisp/com/example/Kernels.lisp` は `com.example.Kernels` になり、ファイルごと
の宣言は不要です。出力は通常の場所に置かれた通常のクラスなので、`mvn install`、
`mvn deploy`、生成された jar を使う Gradle コンシューマ、IDE のいずれも追加の概念なし
に動きます。

ゴールは javac より前の `process-sources` で走ります。`src/main/java` が書き出された
もの (カーネルのクラスと `RontoFloatArray` ハンドル型の両方) に対してコンパイルされる
からです。`testCompile` ゴールはその双子で、`process-test-sources` で `src/test/lisp`
を `target/test-classes` にコンパイルします。

JVM バックエンドに届くフラグはすべて同じ名前のパラメータになります — `simd`、`blas`、
`gpu`、`parallel`、`optimize`、`dynamic`、`noPrune`、`systemPath`、`dists` — そして
`skip` (`-Drontolisp.skip=true`) でゴールを止められます。コマンドラインと既定値が違う
のは 1 つだけ、`noMain` が **オン** であることです。ソースセットはライブラリだからで、
`rontolisp:jvm-export` を持たないファイルは中身のないクラスを生む代わりにビルドを失敗
させます。`main` をエクスポートと並べて残すなら `<noMain>false</noMain>` を指定します。

コンパイルエラーは rontolisp の診断をそのまま持つビルド失敗として報告されます。
`file:line:column:` の接頭辞も含まれるので IDE から飛べます。コンパイルは
`maven-compiler-plugin` と同じ意味でインクリメンタルです。古いものが 1 つもなければ何
もコンパイルせず、1 つでもあればソースセット全体をコンパイルします — `(load "...")`
はあるファイルを別のファイルに差し込むので、ファイル単位のタイムスタンプだけは信用で
きないからです。

## Maven コンシューマ向けのパッケージング

カーネルを使う側とは *別に* ビルドする場合 — 他チームへ配布する、Maven ではない
コンシューマがいる、リポジトリに publish する — は、コンパイラ自身がアーティファクト
を書きます。

`-o out.jar` は直接 jar へコンパイルし、`--maven-coordinates` はその jar に
自身の座標を刻み込みます。

```bash
rontolisp kernels.lisp -o acme-kernels-1.0.0.jar \
    --class-name com.example.Kernels \
    --maven-coordinates com.example:acme-kernels:1.0.0 \
    --no-main
```

ここでの `--class-name` は便利機能ではありません — `.class` のパスは常に
クラス名そのものでしたが、`.jar` のパスはどのクラスも名指ししないからです。
`.class` 出力にも使え、その場合は `-o` のパスが与える名前を置き換えるので、
ビルドディレクトリをパッケージの形に合わせる必要はもうありません。

座標は jar の**内側**に乗ります。Maven でビルドされた jar がすでに持っている
`META-INF/maven/<groupId>/<artifactId>/pom.xml` + `pom.properties` の組がその
場所です。したがってインストールに座標のフラグは一切要りません —
`-DgroupId` も `-DartifactId` も `-Dversion` も `-DpomFile` も不要です。

```bash
mvn install:install-file -Dfile=acme-kernels-1.0.0.jar
```

あとは普通の依存関係です。

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>acme-kernels</artifactId>
    <version>1.0.0</version>
</dependency>
```

`--emit-pom` は同じ pom を `acme-kernels-1.0.0.pom` として jar の隣にも
書き出します。pom を独立したファイルとして欲しい `deploy-file` のためです。
自分で書いていない pom を上書きすることは拒否します。

### jar に入るもの

| エントリ | 条件 |
| --- | --- |
| `META-INF/MANIFEST.MF` | 常に。`Main-Class` はクラスが `main` を持つときだけなので、`java -jar` はプログラムの jar を実行でき、`--no-main` のライブラリ jar は持ちません |
| `com/example/Kernels.class` | 常に |
| `am/ik/rontolisp/runtime/*.class` | `:float-vector` / `:float-matrix` のエクスポートがハンドル型を宣言したとき |
| `META-INF/maven/.../pom.xml`, `pom.properties` | `--maven-coordinates` があるとき |

ハンドルクラスはあなたのパッケージへリネームされず、正準名のままアーティファクトの
中を運ばれます。ある ライブラリの結果を別のライブラリのカーネルへ渡すために、2 つの
rontolisp ライブラリが型について一致していなければならないからです。コピーどうしは
同一のバイト列です。これを入れ忘れてもここではコンパイルエラーになりません —
コンシューマ側の `NoClassDefFoundError` になります。

生成される pom の `<dependencies>` は空で、それは省略ではなく要点です。
コンパイルされたクラスは呼び出すものをすべて埋め込むので、アーティファクトは
本当に依存関係を持ちません。アクセラレーションについて 1 点: `--simd` ビルドが
ベクトルカーネルを得られるのは `--add-modules jdk.incubator.vector` 付きで
起動された JVM の上だけです — モジュールがなければクラスはポータブルな
スカラーカーネルへ縮退し、その旨を出力します。コンシューマはビルドコマンドを
見ていないので、生成される pom も `<description>` でそれを伝えます。`--blas` と
`--gpu` ビルドは実行時にネイティブライブラリを探し、同じように縮退するので、
やはりコンシューマ側には何も要りません。

同じプログラムを 2 回コンパイルすればバイト単位で同一の jar になります。
エントリの順序もタイムスタンプも固定で、時計から取っていません。

## 制限

- エクスポートできるのは固定アリティのトップレベル `defun` のみです。
  `&optional`/`&rest`/`&key` のラムダリストは拒否されます (固定アリティの
  `defun` でラップしてください)。
- パック済み float 配列が渡れるのはランク 1 か 2 です。ランク 3 以上の指定子は
  まだなく、一般 (ボックス化) 配列には指定子がありません — float 以外の配列は
  今も `:bytes` だけです。
- `-o out.jar` が書き出すアーティファクトは 1 つだけです。`-sources` や
  `-javadoc` の jar も署名もありません。それらは `install-file` /
  `deploy-file` 自身のフラグで扱います。
