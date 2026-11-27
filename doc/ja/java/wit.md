# WIT のインタフェースと world

[サブセット](index.md)のプログラムは、`@WitImport` を付けた Java のインタフェースを通して
WIT インタフェースを呼び、`@WitExport` を付けたクラスで WIT world を実装します
（[WIT world をエクスポートする](#exporting-a-wit-world)）。アノテーションは
[`rontolisp-java-api`](https://github.com/making/rontolisp/tree/develop/rontolisp-java-api)
のものです。依存のない普通の Java のライブラリなので、同じソースを `javac` でコンパイルし、
JDK で動かせます。インタフェースの実装はサービスプロバイダです。JDK、インタプリタ、JVM では
プログラム自身の実装が呼ばれ、WASM のモジュールとコンポーネントではホストの実装が呼ばれます。
[`rontolisp:wit-import`](../guides/wit-contracts.md#importing-a-wit-interface-wit-import) の
Java 側にあたります。

```console
import java.util.List;
import java.util.Optional;

import am.ik.rontolisp.wit.WitImport;

@WitImport(value = "wasi:keyvalue/store", wit = "wit/keyvalue.wit")
public interface Store {

    Bucket open(String identifier);

    interface Bucket extends AutoCloseable {

        Optional<byte[]> get(String key);

        void set(String key, byte[] value);

        KeyResponse listKeys(Optional<Long> cursor);

        @Override
        void close();
    }

    record KeyResponse(List<String> keys, Optional<Long> cursor) {
    }

    sealed interface Error {

        record NoSuchStore() implements Error {
        }

        record AccessDenied() implements Error {
        }

        record Other(String value) implements Error {
        }
    }
}
```

```console
Store store = ServiceLoader.load(Store.class).findFirst().orElseThrow();
try (Store.Bucket bucket = store.open("")) {
    bucket.set("visits", "41".getBytes(StandardCharsets.UTF_8));
}
```

ライブラリはプログラムの[ソースパス](index.md#using-a-third-party-library)に置き、JDK では
クラスパスに置きます。

```bash
API="--java-source-path rontolisp-java-api/src/main/java"
rontolisp PageHits.java $API                                     # interpreter
rontolisp PageHits.java $API -o PageHits.class && java PageHits  # JVM
rontolisp PageHits.java $API -o kv.wasm --component && wasmtime run -S keyvalue=y kv.wasm
java -cp rontolisp-java-api.jar PageHits.java                    # a JDK
```

[`examples/wit/keyvalue/PageHits.java`](https://github.com/making/rontolisp/tree/develop/examples/wit/keyvalue)
は `wasi:keyvalue/store` を使うカウンタで、この 4 通りのどれでも同じ報告を出力します。
コンポーネントの報告は wasmtime 自身のストアから得たものです。

## インタフェース

`value` は WIT インタフェースです。ID か、バージョンを省いた ID を書きます。省いたバージョンは
読んだ WIT から決まります。`wit` は WIT で、`.wit` ファイルか、公開されている形のパッケージの
ディレクトリ（その `.wit` ファイルと `deps/`）を、`Class.getResource` がリソースを指すのと同じ
名前で書きます。インタフェースのパッケージからの相対パスで、先頭が `/` ならルートの最上位からです。
探す場所はインタフェースのクラスローダーと同じで、プログラムのソースツリー、次にソースパスの
ルート（ディレクトリか jar。`--java-source-dep`、Maven プラグインでは先頭の `target/classes`）を
順に探します。インタフェースを宣言するファイルの隣にある WIT も、`src/main/resources` の
パッケージのディレクトリにある WIT も見つかります。

```console
src/main/java/com/example/kv/Store.java          @WitImport(..., wit = "wit/keyvalue.wit")
src/main/resources/com/example/kv/wit/keyvalue.wit
```

`..` はルートの中で解決し、ルートの最上位より上に出るものは拒否します。ファイルから読まない
プログラム（`-e`、セッション）は、ソースパスより先に作業ディレクトリを探します。

インタフェースにはプログラムが呼ぶ関数だけを宣言します。それぞれはプログラムをコンパイルするときに、
どのターゲットでも WIT と照合されます。WIT にない関数や、型の違う引数や戻り値は、WIT のメンバーの
行を添えて、宣言の位置で拒否されます。

```console
Store.java:12:21: listKeys (bucket-list-keys of wasi:keyvalue/store@0.2.0-draft2,
wit/keyvalue.wit:20): its parameter cursor is option<string>, which is Optional<String>
in Java, not Optional<Long>
```

そのため、ある WIT インタフェースの 2 つのバージョンに共通するものだけを宣言したインタフェースは、
`wit` がどちらを指しても照合が通り、インポートのバージョンはその WIT のものになります。

名前は WIT の名前を Java の書き方にしたものです。

| WIT | Java |
| --- | --- |
| 関数 `list-keys` | メソッド `listKeys` |
| リソース `bucket` | メンバーインタフェース `Bucket`。メソッドはリソースのもの |
| リソースの解放 | そのインタフェースの `close()` |
| リソースのコンストラクタ、static 関数 `create` | アノテーションを付けたインタフェースのメソッド `bucketNew`、`bucketCreate` |
| record、variant、enum、flags の `key-response` | メンバー型 `KeyResponse` |
| ケースやラベルの `no-such-store` | record `NoSuchStore`、enum 定数 `NO_SUCH_STORE` |

Java のキーワードになる名前の末尾には `_` が付きます（`default_`）。

## 実装

`ServiceLoader.load(Store.class)` がインタフェースの実装を見つけます。

- **JDK、インタプリタ、JVM** では、プログラム自身の実装です。public のクラスで、引数のない
  public のコンストラクタを持ち、ソースツリーの `META-INF/services/Store`（インタフェースの
  バイナリ名）に名前が書かれているものです。ほかのサービスと同じです。
- **rontolisp がコンパイルするモジュールとコンポーネント**では、ホストの実装だけです。
  モジュールが WIT インタフェースをインポートし、プログラムが挙げたプロバイダは使われません。

プログラムは、自分がどこで動くかを何も書きません。開発の環境ではプログラム自身のストアが使われ、
配置先ではホストのストアが使われます。WASM のターゲットでは、モジュールはプログラムが呼ぶ関数だけを
インポートします。

`--no-wasi` のモジュールは、コンポーネントと同じく canonical ABI でインタフェースをインポートし、
そのホストは `--emit-js-glue` が書き出す JavaScript のグルーです
（[フラットな型の集合に収まらない WIT インターフェース](../guides/wasm-host-boundary.md#wit-interfaces-beyond-the-flat-set)）。
`wasi:keyvalue/store@0.2.0-draft2` にはグルー自身が Workers KV の上で答えるので、状態をそこに置く
プログラムは、そのまま Cloudflare Worker になります。このホストは promise で答えることがあるため、
モジュールのロード中に（`main` から）呼ぶプログラムは拒否されます。

ホストが独自に持つインタフェースも同じようにインポートします。
[`jdbc-spin-postgres`](https://github.com/making/rontolisp/tree/develop/jdbc-spin-postgres)
は Spin の `spin:postgres/postgres` を宣言し（`Postgres.java`）、その上の JDBC ドライバになっています。
PostgreSQL への接続を開き、プールし、`sslmode=require` なら TLS で話すのは Spin です
（[JDBC の「Spin の上で」](jdbc.md#under-spin)）。JDK にはこのインタフェースの実装がなく、そのとき
ドライバは URL をほかのドライバに任せます。

## 型

| WIT | Java |
| --- | --- |
| `bool` | `boolean` |
| `s8`, `u8` | `byte` |
| `s16`, `u16` | `short` |
| `s32`, `u32` | `int` |
| `s64`, `u64` | `long` |
| `f32`, `f64` | `float`, `double` |
| `char` | `int`（コードポイント） |
| `string` | `String` |
| `list<u8>` | `byte[]` |
| `list<T>` | `List<T>` |
| `option<T>` | `Optional<T>` |
| `tuple<A, B>` | その要素を持つ、プログラムの record |
| `record` | メンバーの record。フィールドがコンポーネント |
| `variant` | メンバーの sealed インタフェース。ケースごとに 1 つの record を permits に持つ（ペイロードはその唯一のコンポーネント） |
| `enum` | メンバーの enum |
| `flags` | メンバーの enum の `Set` |
| リソース、`own<R>`、`borrow<R>` | メンバーインタフェース |
| インタフェースが `use` でほかのインタフェースから取るリソース | そのインタフェースを表す `@WitImport` インタフェースのメンバーインタフェース |
| `result<T, E>` | `WitResult<T, E>`。メソッドの戻り値では ok の値にもでき、そのときエラーは `WitException` |
| `async func` | ホストの応答を待つメソッド（ほかの呼び出しと同じ） |
| ホストが返す `stream<T>` | `am.ik.rontolisp.wit.WitStream<T>`（値の stream もリソースの stream も） |
| ホストが返す `stream<u8>` | `java.io.InputStream` |
| プログラムがホストに渡す `stream<u8>` | `java.util.function.Consumer<java.io.OutputStream>`（書き込み側の端を受け取る） |
| プログラムがホストに渡す `option<stream<u8>>` | `Optional<Consumer<OutputStream>>`。stream がなければ空 |
| ホストが返す `future<T>` | `am.ik.rontolisp.wit.WitFuture<T>`。`future<result<T, E>>` は `WitFuture<T>`（ok の値がなければ `Void`） |
| プログラムがホストに渡す `future<T>` | プログラムが作った、同じ型の `WitFuture<T>` |

符号なし整数は同じ幅の Java の型に、そのビットのまま入ります。`u8` の 200 は `byte` の -56
です（`Byte.toUnsignedInt` で 200 に戻ります）。

`result<T, E>` は、WIT のどこにあっても `am.ik.rontolisp.wit.WitResult<T, E>` です。引数、
リストの要素、option の値、record のフィールド、variant のペイロード、戻り値のいずれでも同じです。
これは 2 つの record、`WitResult.Ok(T value)` と `WitResult.Err(E error)` を permits に持つ
sealed インタフェースです。値を持たない側は `Void` で、その値は `null` です。したがって
`result<_, E>` は `WitResult<Void, E>` です。側にある tuple は、ほかの場所と同じくプログラムの
record です。

```console
// check-all: func(names: list<string>) -> list<result<u32, problem>>;
List<WitResult<Integer, Problem>> checkAll(List<String> names);

// record report { name: string, verdict: result<u32, problem> }
record Report(String name, WitResult<Integer, Problem> verdict) {
}
```

```console
switch (report.verdict()) {
    case WitResult.Ok<Integer, Problem>(Integer length) -> System.out.println(length);
    case WitResult.Err<Integer, Problem>(Problem problem) -> System.out.println(problem);
}
```

メソッドの戻り値は、代わりに ok の値の型で宣言することもできます（値がなければ `void`）。
そのときエラーは `am.ik.rontolisp.wit.WitException` として投げられます。その `error()` はエラーを
対応する Java の型で持ちます（ケースの record、enum 定数、文字列。値がなければ `null`）。
バインディングは宣言に従うので、上の `open` は `Bucket` を返し、エラーでは例外を投げます。
ホストのバインディングが付けるメッセージは `<function> of <interface> answered its error arm: `
にエラーの `toString()` を続けたものです（`null` なら前半だけ）。捕捉されなかったエラーも
ログにその内容が残ります。プログラムのプロバイダは、自分のエラーをこの例外で投げます。

```console
try {
    store.open("not-a-store-anyone-has");
}
catch (WitException e) {
    if (e.error() instanceof Store.Error.NoSuchStore) {
        System.out.println("no such store");
    }
}
```

リソースは `close()` で解放します。ハンドルはホストに返り、ストアはそのまま残ります。閉じた
リソースは `IllegalStateException` を投げ、2 度目の `close()` は何もしません。関数に渡した `own`
のハンドルは関数のものになり、オブジェクトは閉じられます。

インタフェースが `use` でほかのインタフェースから取るリソースは、それを定義するインタフェースを
表す `@WitImport` インタフェースに 1 度だけ宣言し、そこから名前で参照します。`wasi:http/client`
の `send` は、`wasi:http/types` の `request` を受け取り、`response` を返します。定義する側の
インタフェースは、自分の関数がそのリソースを受け渡すかどうかにかかわらず、メンバーインタフェースを
宣言します。`use` で取るレコード、variant、enum、flags は、取る側のインタフェースのメンバー型で、
そこにもう 1 度宣言します。

```console
// interface client { use types.{request, response, error-code};
//                    send: async func(request: request) -> result<response, error-code>; }
@WitImport(value = "wasi:http/client@0.3.0", wit = "wit/http.wit")
public interface Client {

    Types.Response send(Types.Request request);

    sealed interface ErrorCode { ... }
}
```

### ホストが少しずつ返すもの

`async func` もほかと同じメソッドで、呼び出しはホストの応答を待ちます。ホストが返す値の
`stream<T>` は `WitStream<T>` です。`read(max)` は、ホストが値を書くか去るまで待って、多くとも
`max` 個の値を返し、終わりでは空のリストを返します。`future<T>` は `WitFuture<T>` で、`get()` は
その 1 つの値を待ちます。`future<result<T, E>>` は ok の値を返し、エラーは `WitException` として
投げます（`result` を返すメソッドと同じです）。どちらも `AutoCloseable` で、`close()` は読み取り側の
端を返し、読まなかった分はホストが捨てます。

```console
// spin:postgres の query-async: 列、サーバーが送るままの行、その終わり
record QueryAsync(List<Column> columns, WitStream<List<DbValue>> rows, WitFuture<Void> done) {
}

QueryAsync started = connection.queryAsync("select id, title from note", List.of());
try (WitStream<List<DbValue>> rows = started.rows(); WitFuture<Void> done = started.done()) {
    for (List<List<DbValue>> chunk = rows.read(50); !chunk.isEmpty(); chunk = rows.read(50)) {
        ...
    }
    done.get();                                // 文が拒否されたならここで投げる
}
```

コンポーネントでは値はホストが書き、1 回の読み取りが持つのはその読み取りの値だけです（stream
全体がどれだけあっても）。リソースの stream は 1 回の読み取りで 1 つのリソースを返します
（`wasi:sockets` の `listen` が受け付ける接続）。JDK、インタプリタ、JVM ではプロバイダが自分の
ものを返します: `WitStream.of(iterator)`、`WitFuture.of(value)`、`WitFuture.failed(exception)`、
または自分のクラスです。

ホストが返す `stream<u8>` は `InputStream` で、ホストが書くのに合わせて読めます。`close()` は
読み取り側の端を返します。プログラムがホストに渡す `stream<u8>` は `Consumer<OutputStream>` 型の
引数です。ホストが読み取り側の端を受け取ると、バインディングは書き込み側の端の `OutputStream` を
その consumer に渡します。プログラムはそれを持っておき、呼び出しの後で書けます。書き込みはホストが
すべて読むまで待ち、`close()` で stream が終わります。ホストが端を捨てた後に書くと `IOException`
を投げます。

```console
// tcp-socket の send: func(data: stream<u8>) -> future<result<_, error-code>>
WitFuture<Void> send(Consumer<OutputStream> data);

OutputStream[] writer = new OutputStream[1];
WitFuture<Void> sent = socket.send(out -> writer[0] = out);
try (OutputStream out = writer[0]) {
    out.write(request);
}
sent.get();
```

読み取りは呼び出しと同じくその場で待ち、プログラムのすべてのスレッドが一緒に待ちます。JDK、
インタプリタ、JVM では、プロバイダが自分の `OutputStream` を consumer に渡します。

`option<stream<u8>>` の引数は `Optional<Consumer<OutputStream>>` です。`Optional.empty()` なら
ホストに stream を渡さず、consumer なら上と同じく書き込み側の端を受け取ります。

### プログラムがホストに渡す future

`future<T>` の引数は、プログラムが作った `WitFuture<T>` です。`WitFuture.of(value)`、
`future<result<T, E>>` のエラーの側なら `WitFuture.failed(exception)`（`error()` がその Java の型の
エラーである `WitException`）、または自分のクラスです。コンポーネントでは、バインディングが
呼び出しの前に `get()` を 1 度呼び、その値を、ホストが future を読むときに見つかる場所に置きます。
ホストが読むのは、その呼び出しの中でも後の呼び出しの中でも構いません。呼び出しはホストが読むのを
待たず、プログラムが後で解放するものもありません。JDK、インタプリタ、JVM では、プロバイダが
オブジェクトを受け取り、読むときに `get()` を呼びます。

```console
// request.new: static func(headers: headers, contents: option<stream<u8>>,
//     trailers: future<result<option<trailers>, error-code>>, options: option<request-options>)
//     -> tuple<request, future<result<_, error-code>>>;
Sent requestNew(Fields headers, Optional<Consumer<OutputStream>> contents,
        WitFuture<Optional<Fields>> trailers, Optional<RequestOptions> options);

record Sent(Request request, WitFuture<Void> transmission) {
}
```

```console
Types.Sent sent = types.requestNew(types.fieldsNew(), Optional.empty(),
        WitFuture.of(Optional.empty()), Optional.empty());      // ボディも trailers もなし
sent.request().setAuthority(Optional.of("example.com"));
Types.Response response = client.send(sent.request());          // レスポンスを待つ
Types.Body body = types.responseConsumeBody(response, WitFuture.of(null));
try (InputStream in = body.contents()) {
    System.out.println(new String(in.readAllBytes(), StandardCharsets.UTF_8));
}
```

これで、プログラムは `wasmtime run -S http=y` の下で `wasi:http@0.3.0` の HTTP クライアントに
なります。ボディのないリクエストを送り、レスポンスを読めます。制限はリクエストのボディです。
`OutputStream` への書き込みはホストが読むまで待ちますが、wasmtime がリクエストのボディを読むのは、
プログラムが待っている `client.send` の中です。すぐに終わるボディ（consumer が stream を閉じる）は
送られ、その後で trailers が future から読まれます。

## WIT world をエクスポートする

`@WitExport` を付けたクラスは WIT world を実装します。world がエクスポートする関数はそれぞれ
クラスの static メソッドで、名前はキャメルケースです。world がエクスポートするインタフェースは
それぞれ、インタフェース名をアッパーキャメルケースにした static メンバークラスで
（`export docs:adder/add;` なら `Add`）、その static メソッドがインタフェースの関数です。
[`rontolisp:wit-export`](../guides/wit-contracts.md#implementing-a-wit-world-wit-export) の
Java 側にあたります。

```console
package example:vowels;

world counter {
  export vowel-count: func(text: string) -> s32;
}
```

```console
import am.ik.rontolisp.wit.WitExport;

@WitExport(value = "counter", wit = "wit/vowels.wit")
public class Counter {

    public static int vowelCount(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if ("aeiouAEIOU".indexOf(text.charAt(i)) >= 0) {
                n++;
            }
        }
        return n;
    }
}
```

`value` は world の名前、`wit` は WIT です。WIT はインタフェースの場合と同じように探します
（[インタフェース](#the-interface)）。

入口のクラスに `main` がないプログラムは、ソースツリーで `@WitExport` が付いたクラスから
始まります。それらのクラスはプログラムに加わり、その world のエクスポートがプログラムの入口に
なります。プログラムはリアクタのコンポーネントにコンパイルされます。コンポーネントは world を
エクスポートし、`wasi:cli/run` はエクスポートしません。エクスポートが最初に呼ばれたときに、
プログラムの static の初期化が実行されます。エクスポートを呼ぶのは、ホストか、合成した
コンポーネントです。

```bash
API="--java-source-path rontolisp-java-api/src/main/java"
rontolisp Counter.java $API -o counter.wasm --component
wasmtime run --invoke 'vowel-count("hello world")' counter.wasm   # 3
```

[`examples/java/rust-calls-java/`](https://github.com/making/rontolisp/tree/develop/examples/java/rust-calls-java)
は、これを `vowel-count` を呼ぶ Rust のコンポーネントと合成します。ビルドには Maven プラグインを
使います。メインクラスを指定しないと、プラグインの `wasm` ゴールは `@WitExport` が付いたクラスを
コンパイルします。

スカラー値のクラス（[`--no-gc` のリアクタ](index.md#a-reactor-on---no-gc)）は、`--no-gc` を
付けてもコンパイルできます。できるのはスカラーのバックエンドのコンポーネントで、static の初期化は
インスタンス化のときに実行されます。上のカウンタの `indexOf` を比較に書き換えたものは、そこで
2,040 バイト、`--no-gc` なしで 12,988 バイトです。`bool`、整数、`f64`、`string` 以外の型を
受け渡す world は、そこでは WIT の行で拒否されます。スカラーのバックエンドには、レコードや
リストにあたる値がないためです。これらの型だけを使う `async func` は、そこでもほかの
コンポーネントと同じようにエクスポートされます。一方、このクラスから `@WitImport` の
インタフェースは呼べません。`ServiceLoader` が返すバインディングはオブジェクトで、スカラーの
バックエンドにはオブジェクトがないため、`--component` の有無にかかわらず、取得する箇所で
拒否されます。

JDK、インタプリタ、JVM では、アノテーションは何もしません。`vowelCount` は普通の static
メソッドで、プログラムやテストから呼びます。`main` のないプログラムは、そこでは拒否されます。
world をエクスポートするのはコンポーネントなので、モジュールと `--native` でも拒否されます。
`main` のあるプログラムはコマンドで、その `@WitExport` のクラスはどこにもエクスポートされません。
どちらもないプログラムはモジュールのリアクタです
（[public static メソッドのリアクタ](index.md#a-reactor-of-public-static-methods)）。

各メソッドは、`main` の有無によらず、どのターゲットでもコンパイル時にエクスポートと照合されます。
メソッドがない、static でない、引数や戻り値の型が違う、といった宣言は、WIT の行を示して宣言の
位置で拒否されます。

```console
Counter.java:6:24: vowelCount (vowel-count of world counter, wit/vowels.wit:4): its result
is s32, which is int in Java, not long
```

### エクスポートの型

型は[上の表](#the-types)のとおりです。ただし、world のエクスポートが受け渡せない型は、
WIT の行で拒否されます。リソース、`stream`、`future` です。名前の付いた型は、インポートする
インタフェースの場合と同じくメンバー型です。
world が定義する型はアノテーションを付けたクラスのメンバー型で、エクスポートするインタフェースが
定義する型は、そのインタフェースのメンバークラスのメンバー型です。world やインタフェースが
`use` でほかのインタフェースから取り込んだ型も、`use` 句が与えた名前のメンバー型です。tuple はプログラムのレコードで、
どこに宣言してもかまいません。

```console
interface geometry {
  record point { x: s32, y: s32 }
  variant shape { circle(f64), rect(point), dot }

  flip: func(p: point) -> point;
  area: func(s: shape) -> result<f64, string>;
}

world shapes {
  record summary { count: u32, total: f64 }

  export geometry;
  export summarize: func(areas: list<f64>) -> summary;
}
```

```console
@WitExport(value = "shapes", wit = "wit/shapes.wit")
public class Shapes {

    public record Summary(int count, double total) {
    }

    public static Summary summarize(List<Double> areas) {
        ...
    }

    public static class Geometry {

        public record Point(int x, int y) {
        }

        public sealed interface Shape {

            record Circle(double value) implements Shape {
            }

            record Rect(Point value) implements Shape {
            }

            record Dot() implements Shape {
            }
        }

        public static Point flip(Point p) {
            return new Point(p.y(), p.x());
        }

        public static double area(Shape s) {
            if (s instanceof Shape.Dot) {
                throw new WitException("a dot has no area", "a dot has no area");
            }
            ...
        }
    }
}
```

```bash
wasmtime run --invoke 'flip({x: 1, y: 2})' shapes.wasm   # {x: 2, y: 1}
wasmtime run --invoke 'area(dot)' shapes.wasm            # err("a dot has no area")
```

`result` を返すメソッドは、インポートするインタフェースのメソッドと同じく、`WitResult<T, E>` か
ok 側の型（値がなければ `void`）を宣言します。ok 側の型を宣言したメソッドは、エラー側を
`WitException` として投げます。その `error()` が、対応する Java の型のエラーです（値がなければ
`null`）。それ以外の例外は、エクスポートが捕まえなかった例外として扱われます
（[制限](#limitations)）。`Optional`、`List`、レコード、`WitResult` の代わりに `null` を返すと、
同じ扱いの `NullPointerException` になります。

[`examples/wit/shapes/Shapes.java`](https://github.com/making/rontolisp/tree/develop/examples/wit/shapes)
は、隣にある Lisp の例と同じ world を実装します。その例の Lisp のコマンドは、どちらの
コンポーネントと合成しても、同じ行を出力します。

符号なし整数は、同じ幅の Java の型にビットのまま入ります。これはどちらの向きでも同じです。
`u32` の 4294967295 は `int` の -1 になり、`u32` を返すエクスポートが `int` の -1 を返すと
4294967295 になります。

`async func` のエクスポートも、ほかと同じく値を返す static メソッドです。使える型もほかの
エクスポートと同じで、レコード、リスト、variant、`result` は `func` と同じように `async func` を
通ります。出力ごとの扱いは次のとおりです。

| 出力 | `async func` のエクスポート |
| --- | --- |
| `--component` | async で lift します。`Thread.sleep` や、インポートした `async func` の呼び出しのようにメソッドがブロックする箇所で、呼び出しは待てます。ブロックする通常の `func` はトラップになります（`cannot block a synchronous task`） |
| `--component --no-wasi` | これも async で lift しますが、コンポーネントは時計をインポートしないので、sleep するメソッドはトラップになります |
| `--no-gc --component` | これも async で lift します。使える型は、そこでのほかのエクスポートと同じく `bool`、整数、`f64`、`string` だけです。スカラーのサブセットには待つ手段がありません。スリープするスレッドも（`Thread.sleep` はそこで拒否されます）、`@WitImport` のバインディングを持つオブジェクトもないためです |
| モジュール（`-o out.wasm` だけ、`--no-wasi`）、`--native` | ほかのエクスポートと同じく、どこにもエクスポートされません。world をエクスポートするのはコンポーネントです |
| JDK、インタプリタ、JVM | ほかと同じように呼ばれる、ただのメソッドです |

```console
export pause: async func(ms: u32) -> u32;
export summarize: async func(areas: list<f64>) -> summary;
```

```console
public static int pause(int ms) throws InterruptedException {
    Thread.sleep(ms);
    return ms;
}

public static Summary summarize(List<Double> areas) throws InterruptedException {
    Thread.sleep(1);
    ...
}
```

## 制限

- WASM のモジュールで使える型は、そのモジュールで `rontolisp:wit-import` が使える型と
  同じです。Preview 1 のコアモジュールでは平坦な型（32 ビットまでの整数、浮動小数点数、
  `bool`、`string`、`list<u8>`、ハンドル）だけです。コンポーネントではすべての型、`--no-wasi` の
  モジュールでは `async func`、`stream`、`future` 以外のすべての型です（`flags` も通り、
  JavaScript のホストからは真偽値のオブジェクトに見えます）。
  それ以外の型を持つメンバーは、プログラムが呼ぶ場合に限り、`wit-import` と同じく WIT の行を
  示して拒否されます。`--no-gc` では、モジュールでもコンポーネントでも `@WitImport` の
  インタフェースは束縛されません。スカラーのバックエンドにはオブジェクトがなく、バインディングを
  取得する箇所で拒否されるためです。
- `Long.MAX_VALUE` を超える `u64` は、どの WASM の境界も越えず、呼び出しはトラップになります。
  例外は、エクスポートが返す複合型の値の中（レコードのフィールド、リストの要素）です。そこでは、
  負の `long` が同じビットの `u64` になります。
- ネイティブ実行ファイル（`--native`）は Preview 1 のモジュールを動かしますが、そのランナーは
  WIT インタフェースを提供しません。そのため、WIT インタフェースを呼ぶプログラムはそこでは起動しません。
- 宣言の位置で拒否されるもの: プログラムがホストに渡す値の stream とリソースの stream（ゲストは
  書けないため）。`async func` に渡す `stream<u8>`（呼び出しが待つ間に、ホストはプログラムが
  呼び出しの後でしか書けないものを読むため）。引数そのものでも引数の `option` でもない位置で
  渡す `stream<u8>` と、引数そのものでない位置で渡す `future`（レコードのフィールド、リストの
  要素）。ホストが返す、`bool`・`list`・`flags`・`option` の `future`（その値を、書き手が捨てた
  future と境界が見分けられないため。プログラムが渡す future はどれでも構いません）。`bool`、
  `list`、`flags`、`option` を要素とする `option`（境界がその none を見分けられないため）。
  ほかのインタフェースのリソースを、そのインタフェースを表す `@WitImport` インタフェース以外の
  場所に宣言したもの。`WitException` として投げるエラーの型にある tuple（型を書く場所がないため。
  `WitResult` の戻り値なら書けます）。
- プログラムが書く stream をホストがいつ読むかは、ホストが決めます。ホストが後の呼び出しの中で
  しか読まない場合、その読み取りを待つ書き込みは戻りません。そのため wasmtime の下では、
  `wasi:http` のリクエストはボディを持てません（wasmtime はボディを `client.send` の中で読みます）。
- world のエクスポートは、リソース、`stream`、`future` を受け渡せません。`async func` のエクスポートで使える型は
  ほかのエクスポートと同じです。`--no-gc --component` では、`async func` を含むすべてのエクスポートで、
  使える型が `bool`、整数、`f64`、`string` だけになります。同じラベルのエクスポート 2 つ（world の関数とインタフェースの
  関数、または 2 つのインタフェースの関数）は、それぞれのクラスの 2 つのメソッドです。
- エクスポートが捕まえなかった例外は、JDK が書く行（`Exception in thread "main"
  java.lang.IllegalStateException: ...`）を標準エラー出力に書いてからトラップになります。
  最初の呼び出しが実行する static 初期化が捕まえなかった例外も同じです。標準エラー出力を
  持たない `--no-wasi` のコンポーネントと、`--no-gc` のコンポーネントは、何も書かずに
  トラップになります。
