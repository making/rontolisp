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
インポートします。そこでは `ServiceLoader.load(Store.class).findFirst()` に `.orElseThrow()` か
`.get()` を続けた式がバインディングそのものになり、ローダは作られません。スカラーのバックエンドには
ローダにあたるオブジェクトがないので、`--no-gc` が受け付ける取得はこの形です。

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

対応は 2 つのアノテーションで共通です。名前の付いた型は、その型を定義する WIT のスコープを
表すクラスのメンバー型です。インポートするインタフェースなら `@WitImport` インタフェース、
world なら、world 自身の型は `@WitExport` のクラス、エクスポートするインタフェースの型は
そのメンバークラスです。各メンバー型は、定義と 1 度ずつ照合されます。

| WIT | Java | 宣言する場所 |
| --- | --- | --- |
| `bool` | `boolean` | |
| `s8`, `u8` | `byte` | |
| `s16`, `u16` | `short` | |
| `s32`, `u32` | `int` | |
| `s64`, `u64` | `long` | |
| `f32`, `f64` | `float`, `double` | |
| `char` | `int`（コードポイント） | |
| `string` | `String` | |
| `list<u8>` | `byte[]` | |
| `list<T>` | `List<T>`（`T` はボックス型） | |
| `option<T>` | `Optional<T>` | |
| `result<T, E>` | `am.ik.rontolisp.wit.WitResult<T, E>`。値を持たない側は `Void`。メソッドの戻り値では ok 側の型にもでき、そのときエラー側は `am.ik.rontolisp.wit.WitException` として投げられる | `rontolisp-java-api` |
| `tuple<A, B>` | その型を順にコンポーネントに持つ record | プログラムのどこでもよい |
| `record` | record。フィールドはローワーキャメルケースのコンポーネント | スコープのクラスのメンバー |
| `variant` | ケースごとに 1 つの record を permits に持つ sealed インタフェース。ペイロードはその唯一のコンポーネント | スコープのクラスのメンバー。record はその中 |
| `enum` | enum。ケースが定数（`NO_SUCH_STORE`） | スコープのクラスのメンバー |
| `flags` | enum の `Set`。ラベルが定数 | スコープのクラスのメンバー |
| 別名（`type id = u32`） | 別名が指す型 | |
| `use` で取り込んだ record、variant、enum、flags | 上と同じ。名前は `use` 句が与えたもの | 取り込む側のスコープのクラスのメンバー |
| リソース、`own<R>`、`borrow<R>` | インタフェース。メソッドがリソースのメソッドで、`close()` が解放 | `@WitImport` インタフェースのメンバー |
| `use` で取り込んだリソース | 定義する側のスコープでそのリソースを表すインタフェース | 定義するインタフェースを表す `@WitImport` インタフェースのメンバー |
| ホストが返す `stream<u8>` | `java.io.InputStream` | |
| プログラムがホストに渡す `stream<u8>` | `java.util.function.Consumer<java.io.OutputStream>`（書き込み側の端を受け取る）。`option` の中なら `Optional<Consumer<OutputStream>>` | |
| 値の `stream<T>`、リソースの `stream<T>` | `am.ik.rontolisp.wit.WitStream<T>` | `rontolisp-java-api` |
| `future<T>` | `am.ik.rontolisp.wit.WitFuture<T>`。`future<result<T, E>>` は `WitFuture<T>`（ok の値がなければ `Void`）で、`get()` がエラー側を投げる | `rontolisp-java-api` |
| `async func` | 値を返すメソッド。インポートではホストの応答を待ち、エクスポートではブロックしてよい | |
| `error-context` | なし。これを使う WIT は拒否される | |

どの型をどこに置けるかは[型を置ける位置](#where-each-type-crosses)、どの出力で使えるかは
[出力ごとに使えるもの](#what-each-output-takes)、符号なし整数、`Long.MAX_VALUE` を超える
`u64`、`null`、閉じたリソースの扱いは[境界での扱い](#at-the-boundary)にまとめています。

実例は、冒頭のキーバリューの `Store`（リソース、`option`、レコード、投げる `result`）、
`QueryAsync` の行とソケットの `send`（[ホストが少しずつ返すもの](#what-the-host-answers-in-pieces)）、
HTTP のリクエスト（[プログラムがホストに渡す future](#a-future-the-program-hands-the-host)）、
`Shapes` の world とその `pause`（[エクスポートの型](#the-types-of-an-export)）です。

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

リソースは `close()` で解放します。ハンドルはホストに返り、ストアはそのまま残ります。関数に
渡した `own` のハンドルは関数のものになり、オブジェクトは閉じられます。

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

[`examples/wit/sockets/Echo.java`](https://github.com/making/rontolisp/tree/develop/examples/wit/sockets) は、
`wasi:sockets@0.3.0` 上の echo で、ここに挙げたものをすべて使い、`async func` と、両方の書き方の
`result` も使います。コンポーネントでは wasmtime 自身のネットワークを、JDK、インタプリタ、
JVM では `java.net` の上に書いたプログラム自身のプロバイダを相手に動き、同じ出力になります。

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
2,040 バイト、`--no-gc` なしで 12,988 バイトです。そこで使えるものは
[出力ごとに使えるもの](#what-each-output-takes)にあります。

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

エクスポートの型は[表](#the-types)のとおりで、名前の付け方も同じです。world の型は
アノテーションを付けたクラスのメンバー型、エクスポートするインタフェースの型はそのメンバー
クラスのメンバー型、`use` で取り込んだ型は取り込む側のスコープのクラスのメンバー型（名前は
`use` 句が与えたもの）で、tuple はプログラムのどの record でもかまいません。リソース、
`stream`、`future` は WIT の行で拒否されます（[型を置ける位置](#where-each-type-crosses)）。

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
（[制限](#limitations)）。

[`examples/wit/shapes/Shapes.java`](https://github.com/making/rontolisp/tree/develop/examples/wit/shapes)
は、隣にある Lisp の例と同じ world を実装します。その例の Lisp のコマンドは、どちらの
コンポーネントと合成しても、同じ行を出力します。

`async func` のエクスポートも、ほかと同じく値を返す static メソッドです。使える型もほかの
エクスポートと同じで、レコード、リスト、variant、`result` は `func` と同じように `async func` を
通ります。コンポーネントはこれを async で lift するので、`Thread.sleep` や、インポートした
`async func` の呼び出しのようにメソッドがブロックする箇所で、呼び出しは待てます。ブロックする
通常の `func` はトラップになります（`cannot block a synchronous task`）。出力ごとの扱いは
[出力ごとに使えるもの](#what-each-output-takes)にあります。

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

[`examples/wit/shapes/Shapes.java`](https://github.com/making/rontolisp/tree/develop/examples/wit/shapes) の `totalArea` は、
sleep してから `WitResult` を返す `async func` です。隣の Lisp のコマンドはインタフェースを
`:blocking t` でバインドし、答えを待ちます。

### スケルトンの生成

`--scaffold-wit` は、出力が `.java` ファイルのとき、または標準出力に書くときに
`--source-language java` を指定したとき、world からクラスを生成します。

```bash
rontolisp --scaffold-wit wit/shapes.wit -o Shapes.java
```

```console
@WitExport(value = "shapes", wit = "wit/shapes.wit")
public class Shapes {

    public record Summary(int count, double total) {
    }

    /**
     * How many areas, and their sum.
     *
     * WIT: {@code summarize: func(areas: list<f64>) -> summary}
     */
    public static Summary summarize(List<Double> areas) {
        throw new UnsupportedOperationException("summarize is not implemented yet");
    }

    public static class Geometry {
        ...
        public static Optional<BoundsResult> bounds(List<Point> ps) {
            throw new UnsupportedOperationException("bounds is not implemented yet");
        }
    }
}
```

各エクスポートは、中身を書くまで例外を投げる static メソッドです。WIT の `///` コメントと
シグネチャが Javadoc になります。各型は[型](#the-types)の表が宣言する場所に宣言されます。
`result` は `WitResult`、タプルは置かれた場所にちなんで名付けたレコード（`BoundsResult`）、
別名があればその名前のレコード（`type ipv4-address = tuple<...>` は `Ipv4Address`）で、
成分は `v0`、`v1` と続きます。world がインポートする各インタフェースは、リソースと型を含めて
クラスの `@WitImport` メンバインタフェースになります。そのいずれかが `use` でリソースを取り込む
インタフェースも同様です。メソッドからは `ServiceLoader.load(Shapes.Store.class)` で取得します。
クラス名はファイル名、標準出力に書くときは world 名です。生成されたファイルはそのまま
コンパイルできるので、エクスポートを 1 つずつ埋めていけます。

アノテーションは WIT を Java ファイルのディレクトリから指すので、WIT はその下に置きます。
そこから上にさかのぼるパスはコンパイル時に拒否され、生成時には警告が出ます。

検査が拒否するものは生成しません。生成器は省いた項目を、検査と同じ文言で標準エラーと
ファイルの先頭に書きます。

```console
rontolisp: warning: --scaffold-wit left out flag (flag of test:skip/host, wit/app.wit:4):
its parameter x is option<bool>, an option of a type one of whose values the boundary
cannot tell from none
```

省かれたインポートの関数は、インタフェースに宣言されないだけです。インタフェースは
プログラムが呼ぶものを宣言するからです。省かれたエクスポートがあると（リソースを
エクスポートする world、world 自身の関数をインポートする world、ここで拒否される型を
エクスポートする world）、WIT を変えるまで検査が拒否するクラスになります。

## 型を置ける位置

その型を使える出力（[出力ごとに使えるもの](#what-each-output-takes)）で、宣言のどこに
何を置けるかを示します。拒否はどのターゲットでもコンパイル時に行われ、Java の宣言の位置で、
WIT のメンバーの行を示します。

| WIT | インポートの引数 | インポートの戻り値 | エクスポートの引数 | エクスポートの戻り値 | ほかの型の中 |
| --- | --- | --- | --- | --- | --- |
| スカラー、`char`、`string`、`list`、`tuple`、`record`、`variant`、`enum`、`flags` | 可 | 可 | 可 | 可 | 可 |
| `option<T>` | 可 | 可 | 可 | 可 | 可 |
| `bool`、`list`、`flags`、`option` を要素とする `option` | 拒否（none と見分けられない値がある） | 拒否 | 拒否 | 拒否 | 拒否 |
| `result<T, E>` | 可 | 可。ok 側の型にしてエラー側を投げてもよい | 可 | 可。ok 側の型にしてエラー側を投げてもよい | 可（`WitResult`） |
| `WitException` として投げるエラー側の tuple | | 拒否（型を書く場所がない。`WitResult` を返す） | | 拒否（同じ） | |
| インタフェースのリソース | 可。渡した `own` はオブジェクトが閉じられる | 可 | 未対応（Java のクラスはリソースをエクスポートしない。Common Lisp のプログラムはできる） | 未対応 | インポートでは可（レコードのフィールド、リストの要素） |
| `use` で取り込んだリソース | 可。もう一方のインタフェースを表す側に宣言する | 可（同じ） | 未対応（Common Lisp のプログラムは、コンポーネントがインポートするリソースを受け取れる） | 未対応 | インポートでは可 |
| `stream<u8>` | 可（`Consumer<OutputStream>`）。ただし `async func` には渡せない（呼び出しが待つ間に、ホストはプログラムが後で書くものを読むため） | 可（`InputStream`） | 拒否（エクスポートは stream を受け渡さない） | 拒否 | インポートの戻り値では可。引数では、それ自身の `option` の中だけ |
| 値の `stream<T>`、リソースの `stream<T>` | 拒否（ゲストは書けない） | 可（`WitStream<T>`） | 拒否 | 拒否 | インポートの戻り値では可 |
| `future<T>` | 可（プログラムが作った `WitFuture<T>`） | 可。ただし `bool`、`list`、`flags`、`option` の future は不可（その値を、書き手が捨てた future と見分けられない） | 拒否（エクスポートは future を受け渡さない） | 拒否 | インポートの戻り値では可。引数では拒否 |
| `error-context` | 拒否（WIT が定義しない型を使うとして、WIT ごと拒否される） | | | | |

エクスポートするインタフェースがリソースを宣言する場合と、その `use` 句がリソースまたは
ハンドルを含む型を取り込む場合は、関数がその型を使うかどうかにかかわらず、world の
`export` の行で拒否されます。world 自身の `use` で取り込んだリソースは宣言されず、それを使う
world の関数は拒否されます。Common Lisp のプログラムはコンポーネントからリソースを
エクスポートでき、コンポーネントがインポートするリソースのハンドルも受け取れます
（[`rontolisp:wit-export`](../reference/functions/rontolisp-wit-export.md#resources)）。
Java のプログラムは、エクスポートされたリソースを、ほかのリソースと同じくそのコンポーネント
からインポートできます。

## 出力ごとに使えるもの

| 出力 | `@WitImport` | `@WitExport`（`main` のないプログラム） | `async func` のエクスポート | エクスポートが捕まえなかった例外 |
| --- | --- | --- | --- | --- |
| `--component` | [型を置ける位置](#where-each-type-crosses)のすべての型 | world をエクスポートするリアクタのコンポーネント | async で lift する。メソッドがブロックする箇所で呼び出しは待てる | JDK と同じ行を標準エラー出力に書いてから、呼び出しがトラップになる |
| `--component --no-wasi` | 拒否（このコンポーネントは何もインポートしない） | `--component` と同じ | async で lift するが、時計をインポートしないので、sleep するメソッドはトラップになる | 何も書かずにトラップになる |
| `--no-wasi` のモジュール（ホストは `--emit-js-glue` のグルー） | canonical ABI を通して、`stream` と `future` 以外のすべての型。`async func` は不可。`main` からは呼べない（JavaScript のホストが promise で応答しうるため） | 拒否（world をエクスポートするのはコンポーネント） | | |
| Preview 1 のモジュール（`-o out.wasm`） | 平坦な型（32 ビットまでの整数、浮動小数点数、`bool`、`string`、`list<u8>`、ハンドル）。それらの型の `async func` は普通の呼び出し | 拒否 | | |
| `--no-gc` のモジュール | `bool`、整数、`f64`、`string`。Preview 1 のモジュールと同じくインポートし、[実装](#the-implementation)の取得で得る。それらの型の `async func` は呼び出し | 拒否 | | |
| `--no-gc --component` | `bool`、整数、`f64`、`string` だけ。同じ取得で得る。ほかの型は WIT の行で拒否。`async func` は待つ呼び出しで、`async func` でないエクスポートから届くと拒否 | それらの型だけ。ほかの型は WIT の行で拒否 | それらの型で async で lift する。インポートが待つところで呼び出しが待てる | 何も書かずにトラップになる |
| `--native` | なし（ランナーが WIT インタフェースを提供しない。平坦な型のメンバーを呼ぶプログラムは起動せず、ほかのメンバーは拒否される） | 拒否 | | |
| JDK、インタプリタ、JVM | プログラム自身のプロバイダ。すべての型を、何も変換せずに渡す | アノテーションは何もしない。普通の static メソッドで、`main` のないプログラムは拒否される | ほかと同じように呼ばれるメソッド | ほかの例外と同じ |

JDK 以外のすべてのターゲットで、宣言は WIT と照合されます。ターゲットが受け渡せない型を持つ
メンバーは、プログラムが呼ぶ場合に限り、`wit-import` と同じく WIT の行を示して拒否されます。

## 境界での扱い

- 符号なし整数は、同じ幅の Java の型にビットのまま入ります。どちらの向きでも同じです。
  `u8` の 200 は `byte` の -56 で（`Byte.toUnsignedInt` で 200 に戻ります）、`u32` を返す
  エクスポートが `int` の -1 を返すと 4294967295 になります。
- `Long.MAX_VALUE` を超える `u64` は、どの WASM の境界も越えません。それを返すインポートも、
  それを渡されたエクスポートもトラップになります。`u64` を返すエクスポートが負の `long` を
  返してもトラップになりますが、エクスポートが返す複合型の値の中（レコードのフィールド、
  リストの要素）では、同じビットの `u64` になります。
- `null` は WIT の値ではありません。WASM のターゲットでは、インポートに `null` を渡す呼び出しも、
  `null` を返すエクスポートもトラップになります。戻り値が `Optional`、`List`、レコード、
  `WitResult` なら、トラップの前に `NullPointerException` を書きます。JDK、インタプリタ、JVM
  では、プロバイダがその `null` を受け取ります。
- 閉じたリソースは `IllegalStateException` を投げ（`tcp-socket of wasi:sockets/types@0.3.0
  is closed`）、2 度目の `close()` は何もしません。閉じた `InputStream` の読み取りは
  `IOException` を投げます。

## 制限

- プログラムが書く stream をホストがいつ読むかは、ホストが決めます。ホストが後の呼び出しの中で
  しか読まない場合、その読み取りを待つ書き込みは戻りません。そのため wasmtime の下では、
  `wasi:http` のリクエストはボディを持てません（wasmtime はボディを `client.send` の中で読みます）。
- エクスポートが捕まえなかった例外と、最初の呼び出しが実行する static 初期化が捕まえなかった
  例外は、JDK が書く行（`Exception in thread "main" java.lang.IllegalStateException: ...`）を
  標準エラー出力に書いてからトラップになります。標準エラー出力を持たない `--no-wasi` の
  コンポーネントと、`--no-gc` のコンポーネントは、何も書かずにトラップになります。
- 同じラベルのエクスポート 2 つ（world の関数とインタフェースの関数、または 2 つの
  インタフェースの関数）は、それぞれのクラスの 2 つのメソッドです。
