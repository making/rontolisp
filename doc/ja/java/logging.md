# ログ

[サブセット](index.md)のプログラムは、`java.util.logging`、またはそれに結び付けた SLF4J 2 や Commons Logging でログを書きます。レコードの行き先はプログラムをどのビルドで動かすかで決まり、プログラムのソースは変わりません。

| ビルド | レコードの行き先 |
| --- | --- |
| インタプリタ、JVM、Preview 1 のモジュール、コンポーネント、ネイティブ実行ファイル | 標準エラー。JDK の既定の構成と同じく、ルートロガーの `ConsoleHandler` を通る |
| `--wasi-logging` を付けてビルドしたコンポーネント | ホストの `wasi:logging/logging` |
| `--wasi-logging` を付けてビルドした `--no-wasi` のモジュール(Cloudflare Worker) | `--emit-js-glue` が書くグルーを通って、レコードのレベルに応じた console のメソッド |

[`examples/java/logging/`](https://github.com/making/rontolisp/tree/develop/examples/java/logging)
には、もう少し長いプログラムがあります。自前のハンドラーとフォーマッターを使い、`slf4j-jdk14` を通して SLF4J でも、SLF4J を通して Commons Logging でも書き、`--wasi-logging` でビルドすると同じレコードがホストのログへ行きます。4 つのターゲットと JDK での出力も載せています。

## java.util.logging

ロガー、レベル、ハンドラー、フォーマッター、レコードは JDK のものと同じように振る舞い、構成ファイルは読みません。ルートロガーのレベルは `INFO` で、ハンドラーを 1 つ持ちます。別のレベル、フォーマッター、自前のハンドラーが要るプログラムは、JDK で動かすときと同じようにプログラム自身が設定します([使える JDK のクラス](classes.md)、[違い](deviations.md#logging))。

```java
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class Orders {
    static final Logger LOG = Logger.getLogger("shop.orders");

    public static void main(String[] args) {
        Handler handler = new Handler() {
            public void publish(LogRecord r) {
                System.out.println(r.getLevel() + " " + r.getLoggerName() + " " + r.getSourceClassName() + "."
                        + r.getSourceMethodName() + ": " + new Formatter() {
                            public String format(LogRecord record) {
                                return formatMessage(record);
                            }
                        }.format(r));
            }

            public void flush() {
            }

            public void close() {
            }
        };
        LOG.setUseParentHandlers(false);
        LOG.addHandler(handler);
        LOG.info("placed");
        LOG.log(Level.WARNING, "{0} left of {1}", new Object[] {2, "tea"});
        LOG.fine("below the logger's level");
    }
}
```

```
INFO shop.orders Orders.main: placed
WARNING shop.orders Orders.main: 2 left of tea
```

## SLF4J

`slf4j-jdk14` で `java.util.logging` に結び付けた SLF4J 2 は、公開されているソース jar から、プログラムの依存としてコンパイルします(`--java-source-dep`、または [Maven プロジェクト](index.md#a-maven-project)の依存)。プロバイダーは JDK と同じく `ServiceLoader` で見つかります。レコードはどれも `java.util.logging` のレコードなので、ほかのレコードと同じ行き先へ送られます。レコードに入る呼び出し元のクラスとメソッドは、JDK でバインディングが求めるものと同じく、SLF4J を呼んだ側のものです。

```console
$ rontolisp App.java --java-source-dep org.slf4j:slf4j-jdk14:2.0.20
$ rontolisp App.java --java-source-dep org.slf4j:slf4j-jdk14:2.0.20 -o app.wasm --component
```

## Commons Logging

Commons Logging 1.3 も、SLF4J と同じくソース jar からコンパイルします(`--java-source-dep commons-logging:commons-logging:1.3.6`)。実装の選び方は JDK と同じです。SLF4J があれば SLF4J を通して書き、なければ自前の `Jdk14Logger`(`java.util.logging` のロガー)で書きます。レコードには、JDK でスタックから求めるのと同じく、Commons Logging を呼んだメソッドが入ります。

Commons Logging は `Jdk14Logger` を保持しているクラス名から探します。コンパイルしたプログラムがその名前でクラスを見つけるには、到達可能性メタデータでの宣言が要ります([到達可能性メタデータによるリフレクション](index.md#reflection-from-reachability-metadata))。プログラム自身のメタデータか、`--java-metadata-repository` で読む共有リポジトリのものです。宣言がなければ、`LogFactory.getLog` は `LogConfigurationException: No suitable Log implementation` を投げます。

```json
{ "reflection": [ { "type": "org.apache.commons.logging.impl.Jdk14Logger",
  "methods": [ { "name": "<init>", "parameterTypes": [ "java.lang.String" ] } ] } ] }
```

```console
$ rontolisp App.java --java-source-dep commons-logging:commons-logging:1.3.6 --java-metadata-repository 1.1.14
```

## ホストのログ

`--wasi-logging` を付けると、ルートロガーのハンドラーが、標準エラーへ書くものから、ホストのログ `wasi:logging/logging`(`0.1.0-draft`)へ渡すものに替わります。ハンドラーはレコード 1 件をエントリー 1 つとして渡します。渡すのはレベル、コンテキストとしてのロガー名、メッセージとしてのレコードのテキストです。テキストは、`formatMessage` の結果に、例外があれば `printStackTrace` の出力を続けたものです。ハンドラーにフォーマッターを与えた場合はそのフォーマッターの出力で、どちらも末尾の改行を 1 つ除きます。ハンドラーはコンソールと同じく `INFO` 以上を通すので、このフラグで変わるのはレコードの行き先だけで、どのレコードが出るかは変わりません。

| `java.util.logging` | `wasi:logging` |
| --- | --- |
| `FINEST`、`FINER` | `trace` |
| `FINE`、`CONFIG` | `debug` |
| `INFO` | `info` |
| `WARNING` | `warn` |
| `SEVERE` | `error` |
| `SEVERE` より上のレベル | `critical` |

**コンポーネント**は、このフラグを付けてビルドするとこのインタフェースをインポートするので、これを提供するホストでしか動きません。wasmCloud は提供しています。`wasmtime run`(51)は提供しておらず、インスタンス化の時点で拒否します(`component imports instance wasi:logging/logging@0.1.0-draft, but a matching implementation was not found in the linker`)。フラグなしでビルドしたコンポーネントは、ログのためのインポートを持ちません。

```console
$ rontolisp App.java -o app.wasm --component --wasi-logging
```

**Cloudflare Worker**(`--no-wasi` のモジュール)は、このインタフェースを canonical ABI でインポートし、`--emit-js-glue` が書くグルーがそれを実装します。`defaultHost()` はレコードをレベルに応じた console のメソッドへ渡します。`trace` と `debug` は `console.debug`、`info` は `console.info`、`warn` は `console.warn`、`error` と `critical` は `console.error` で、メッセージの前にロガー名を置きます(`shop.orders: placed`)。この実装は Promise を返さずに終わるので、インスタンスの起動中にログを書いてもかまいません。

```console
$ rontolisp Server.java -o src/worker.wasm --no-wasi --wasi-logging --optimize=size --emit-worker-entry
```

Maven プラグインのパラメータは `wasiLogging` です([Maven プラグイン](../compiling/maven-plugin.md))。
