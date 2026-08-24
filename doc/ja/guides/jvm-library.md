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

## Maven コンシューマ向けのパッケージング

クラスファイルはすでにパッケージディレクトリの中にあるので、jar は 1
コマンドで作れ、ローカルリポジトリにインストールすれば普通の依存関係に
なります。

```bash
jar cf acme-kernels-1.0.0.jar com/
mvn install:install-file -Dfile=acme-kernels-1.0.0.jar \
    -DgroupId=com.example -DartifactId=acme-kernels -Dversion=1.0.0 \
    -Dpackaging=jar
```

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>acme-kernels</artifactId>
    <version>1.0.0</version>
</dependency>
```

スカラー/文字列のライブラリは実行時にこれ以外何も必要としません — クラスは
自己完結しています。アクセラレーションについて 1 点: `--simd` ビルドは
コンシューマの JVM に `--add-modules jdk.incubator.vector` を要求します。
`--blas` と `--gpu` ビルドは実行時にネイティブライブラリを探し、なければ
ポータブルなカーネルへ縮退するので、コンシューマ側には何も要りません。

## 制限

- エクスポートできるのは固定アリティのトップレベル `defun` のみです。
  `&optional`/`&rest`/`&key` のラムダリストは拒否されます (固定アリティの
  `defun` でラップしてください)。
- パック済み float 配列 (`linalg:`/`vec:` の値) はまだ境界型ではないため、
  数値配列 API は現在 `:bytes` (双方向でコピー) を通るか、Lisp 側に
  とどまります。
- 上の jar は自前の Maven メタデータも `Main-Class` も持ちません。座標は
  `install:install-file` のフラグで渡します。
