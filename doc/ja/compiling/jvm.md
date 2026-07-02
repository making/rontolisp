# JVM バイトコードへのコンパイル

`-o` で `.class` で終わる出力パスを `rontolisp` に渡すと、ソースをインタプリタで実行する代わりに JVM バイトコードへ直接コンパイルします。ASM などのライブラリは使わず、バイトコードは手作業で出力されます。バックエンドを選択するのは出力の拡張子です（JVM なら `.class`、WASM なら `.wasm`）。

```bash
echo '(print (+ 1 2))' > hello.lisp
rontolisp hello.lisp -o Hello.class
java Hello
```

生成されるクラスは出力ファイルにちなんで命名されるため、`java` に渡す名前はファイルの語幹（ステム）になります。`-o Hello.class` はクラス `Hello` を生成し、`java Hello` で実行します。クラス名が一致しなければならないため、パスにディレクトリを含めないでください（`out/Hello.class` ではなく、単純な `Hello.class` を使用します）。プログラムのトップレベルのフォームはクラスのエントリーポイントになり、起動時に順番に実行されます。

例（`hello.lisp`）:

```lisp
(print (+ 1 2))
```

```
3
```

生成される `.class` ファイルは Java 6（クラスバージョン 50）をターゲットとするため、そのバイトコードは JRE 6 以降であれば読み込めます。`java.lang` と `java.io` のほか、出力されるランタイムヘルパーは `java.math`（オーバーフロー時に昇格する整数演算と厳密な有理数演算のための `BigInteger`/`BigDecimal`/`MathContext`）と `java.util`（`ArrayList`/`Arrays`、およびハッシュテーブル用の `HashMap`）を参照します。これらはいずれも Java 6 にすでに存在します。例外の 1 つは `rontolisp:fetch` を呼び出すプログラムで、これは追加で `java.net`/`java.net.http` を参照するため、JRE 11 以降が必要です。もう 1 つは [`java:` 連携パッケージ](../guides/java-interop.md)を使うプログラムで、コンパイラが (プロジェクト自身の Java リリースでコンパイルされた) リフレクションブリッジをクラスに埋め込むため、rontolisp をビルドした JRE と同等以上に新しい JRE が必要です。
