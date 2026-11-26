# PostgreSQL に JDBC でつなぐ

JDBC に対して書いたプログラムは、この[サブセット](index.md)がコンパイルできるドライバーを
使えば、[ソケット](index.md#sockets)を持つすべてのターゲットで PostgreSQL サーバーに接続できます。
[`jdbc-postgresql`](https://github.com/making/rontolisp/tree/develop/jdbc-postgresql)
がそのようなドライバーです。JDK 自身のクラスだけで動き、`jdbc:postgresql:` で始まる URL を
受け付けます。`DriverManager` はサービスファイルを通してこのドライバーを見つけます。現在は
このリポジトリにあり、rontolisp には依存していません。

ドライバーのソースとリソースをプログラムの[ソースパス](index.md#using-a-third-party-library)に
置くと、プログラムと一緒にコンパイルされます。

```console
public class Items {

    public static void main(String[] args) throws SQLException {
        String url = "jdbc:postgresql://127.0.0.1:5432/postgres";
        try (Connection connection = DriverManager.getConnection(url, "postgres", "secret");
                Statement statement = connection.createStatement()) {
            statement.execute("drop table if exists items");
            statement.execute("create table items (id integer primary key, name text)");
            try (PreparedStatement insert = connection.prepareStatement("insert into items values (?, ?)")) {
                insert.setInt(1, 1);
                insert.setString(2, "お茶");
                insert.executeUpdate();
            }
            try (ResultSet rows = statement.executeQuery("select id, name from items order by id")) {
                while (rows.next()) {
                    System.out.println(rows.getInt(1) + " " + rows.getString(2));
                }
            }
        }
    }

}
```

（import するのは `java.sql` の 6 つのクラスです）

```bash
LIBS="--java-source-path jdbc-postgresql/src/main/java:jdbc-postgresql/src/main/resources"
rontolisp Items.java $LIBS                                     # interpreter
rontolisp Items.java $LIBS -o Items.class && java Items        # JVM
rontolisp Items.java $LIBS --native -o items && ./items        # native executable
rontolisp Items.java $LIBS -o items.wasm --component && wasmtime run -S inherit-network=y items.wasm
```

JDK では、同じプログラムが、このドライバーの jar をクラスパスに置いても、他の PostgreSQL
ドライバーを置いても動きます。プログラムはどちらのクラスも名指ししないからです。プログラムが
持つのは、ドライバーのうち到達する部分だけです。ログインしてクエリを 1 つ実行するコンポーネントは
約 780 KB、`PreparedStatement` と非 ASCII の文字列を使うものは約 810 KB です。素の Preview 1
モジュールにはネットワークがないので、接続は `SQLException` で終わり、その原因はソケットの例外です。

このドライバーは、`trust`、`password`、`md5`、SCRAM-SHA-256 でログインします。文、
プリペアドステートメント、バッチを実行し、値を `java.lang`、`java.math`、`java.sql`、`java.time` の
クラスとして読みます。トランザクションとセーブポイントを扱い、サーバーが拒否した内容は、その
SQLSTATE に対応する `SQLException` のクラスで返します。`DatabaseMetaData` のテーブル、列、キー、
インデックスについての問い合わせに答え、クラス名を渡されたプールのための `DataSource`
（`PostgresDataSource`）にもなります。持っている機能と拒否する機能（TLS、`CallableStatement`、
COPY、配列、更新可能な結果セット）は
[README](https://github.com/making/rontolisp/blob/develop/jdbc-postgresql/README.md) にあります。

[`examples/java/jdbc/`](https://github.com/making/rontolisp/tree/develop/examples/java/jdbc)
には、もう少し長いプログラムがあります。サーバーのアドレスを `DATABASE_URL` から受け取り、行を
レコードに読み込み、更新と削除をし、トランザクションを 1 つコミットし 1 つロールバックします。
4 つのターゲットと JDK での出力も載せています。
[`examples/java/shiori/`](https://github.com/making/rontolisp/tree/develop/examples/java/shiori)
は Web アプリケーションで、リクエストが使った接続をプールのように次のリクエストのために
残しておきます（[Servlet アプリケーション](servlet.md)）。

TLS を求めるサーバーには、接続を持つホストを通して、もう 1 つのドライバでつなぎます
（[Spin の上で](#under-spin)）。

## 異なる点

- **コンポーネントにはホストのネットワークが必要です。** `-S inherit-network=y` を付け、
  URL がサーバーを名前で指すときは `-S allow-ip-name-lookup=y` も付けます。
- **JDK の外では、大きな結果をたどるのに時間がかかります。** 1 メガバイト（10,000 行）の
  行を読むのに、ネイティブ実行ファイル、コンポーネント、JVM で 15〜40 ms、インタプリタで約 0.1 秒
  かかります（JDK では約 10 ms）。すべての行の値を 1 つずつ取り出すのに、さらに 0.1〜0.15 秒、
  インタプリタでは約 1 秒かかります。ネイティブ実行ファイルとコンポーネントでは、小さなクエリは
  1 ミリ秒未満、ログインは約 5 ms です。
- **SCRAM-SHA-256 のログインは `.wasm` で約 80 ms かかります。** この方式が求める鍵導出の
  時間です。

## Cloudflare Worker の上で

`--host-sockets` を付けてビルドした `--no-wasi` のモジュールは、ホストのソケットを通して
サーバーに接続します。Cloudflare Worker では、これは `cloudflare:sockets` の `connect()` です。
[Hyperdrive](https://developers.cloudflare.com/hyperdrive/) のバインディングが渡す接続文字列も、
同じ `connect()` で接続します。

```bash
rontolisp Visits.java $LIBS -o src/worker.wasm --no-wasi --host-sockets --host-random --host-output --optimize=size --emit-js-glue
```

`--host-random` は、SCRAM-SHA-256 のログインが使う nonce のために付けます。Worker の上で
動かすプログラムは、次の 3 点を守る必要があります。

- **接続は、リクエスト 1 つの中で使い切ります。** Worker のソケットは、それを開いたリクエストと
  一緒に終わります。そのため、リクエストごとに接続し、ステートメントを実行し、閉じます。
  接続のプールは Hyperdrive が持ちます。次のリクエストまで持ち越した接続は、そこで最初に
  使ったときに `SQLNonTransientConnectionException`（SQLSTATE `08006`）で失敗します。
- **`main` からはデータベースに接続できません。** `main` はインスタンスの開始時に、どの
  リクエストにも属さずに実行されます。そこではソケットを待てないので、インスタンスが終了し、
  Worker のログにその呼び出しの名前が出ます。（`--host-timer` も付けてビルドすると `main` からも
  接続できますが、その接続は最初のリクエストと一緒に終わります。）
- **サーバーのアドレスはリクエストで渡します。** Worker では `System.getenv` が `null` を
  返します。そのため、Worker の `index.js` が、バインディングの接続文字列を、プログラムへ渡す
  リクエストのヘッダーフィールドとして渡します。

[`examples/cloudflare-workers/hello-jdbc/`](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/hello-jdbc)
は、この形の Worker（訪問カウンター）で、大きさは約 1.0 MB（gzip で 270 KB）です。
`wrangler dev` の下でループバックの PostgreSQL に対して測ると、接続してステートメントを
2 つ実行して閉じるリクエストは、`trust` のログインで約 20 ms、SCRAM-SHA-256 で約 210 ms
かかりました。差は SCRAM の鍵導出で、接続のたびにかかります。デプロイした Worker では
測っていません。このドライバーは TLS を話さないので、Worker に TLS を要求するサーバーには
接続できません。

## Spin の上で

[`jdbc-spin-postgres`](https://github.com/making/rontolisp/tree/develop/jdbc-spin-postgres)
は、同じ URL を受け取るもう 1 つのドライバです。このドライバはソケットを開きません。文はすべて、ホストである
[Spin](https://spinframework.dev)（4.1.0 以降）の [WIT インタフェース](wit.md)
`spin:postgres/postgres` で実行します。接続を開き、プールし、サーバーの証明書を検証して TLS で話すのは
Spin です。JDBC のプログラムはドライバを替えるだけで、ほかは変わりません。

```bash
rontolisp App.java -o app.wasm --component \
  --java-source-path jdbc-spin-postgres/src/main/java:jdbc-spin-postgres/src/main/resources:rontolisp-java-api/src/main/java
```

コンポーネントは `spin up` で動かし、つないでよいサーバーを `spin.toml` に書きます
（`allowed_outbound_hosts = ["postgres://host:port"]`）。URL の `sslmode=require` で TLS を求め、
自前の CA の証明書は `sslrootcert` プロパティに PEM のテキストで渡します。
[`examples/java/spin-postgres/`](https://github.com/making/rontolisp/tree/develop/examples/java/spin-postgres)
は、これを平文と TLS の両方で行うサーブレットです。コンポーネントは 1.5 MB で、同じプログラムを
ソケットのドライバでコンパイルすると 1.6 MB になります。

このインタフェースを実装するホストは Spin だけで、JDK、インタプリタ、JVM には実装がありません。
実装がないとき、このドライバは URL を引き受けず、`DriverManager` は次のドライバに進みます。
そのため、両方のドライバをパスに置いたプログラムは、Spin の上ではホストの接続を使い、それ以外では
ソケットでつなぎます。コンポーネントはこのドライバだけでコンパイルします（ネイティブ実行ファイルと
Preview 1 モジュールは、このインタフェースをインポートできません）。

このインタフェースは文を受け取って行を返すだけなので、ドライバが引き受けられないものがあります。
どれも `SQLFeatureNotSupportedException` になります。

- **`timestamptz` の列があると、クエリ全体が失敗します。** 例外のメッセージに型の名前が入ります。
  文の中でキャストしてください（`ts AT TIME ZONE 'UTC'`、または `ts::text`。後者は `getTimestamp` と
  `getObject(i, OffsetDateTime.class)` で時刻として読めます）。
- **パラメータには必ず型が付きます。** `setObject(i, x, Types.OTHER)` はテキストとして送るので、json や
  enum は文の中でキャストします（`?::jsonb`）。`setTimestamp` はタイムゾーンのない `timestamp` を送り、
  `OffsetDateTime` と `Instant` は時刻そのものを送ります。
- **`cancel` と、複数のコマンドを含む文はありません。** ホストが変換できない型（`timetz`、`inet`、
  enum、`float8` の配列）は、テキストにキャストして読みます。

カタログ（`getTables`、`getColumns`、キー、インデックス）は `jdbc-postgresql` と同じ答えを返します。
プリペアドステートメントは実行前に調べられます（クエリの列と、すべての文のパラメータ）。`int4`、`int8`、
`numeric`、`text` の配列は `getArray` で読めます。`createArrayOf` と `setArray` は、どの要素型も受け取ります。

クエリの行はまとめて渡されます。結果セットが前にだけ進む文で `setFetchSize(n)`（`n > 0`）を指定すると、
ホストがサーバーから読むのに合わせて、多くとも `n` 行ずつ読みます。クエリが何行を返しても
プログラムが持つのは 1 回分だけで、最初の行も早く届きます。行を読んでいる途中でサーバーが拒否した文は、
行が尽きたところで例外を投げます。途中で閉じた結果セットの残りはホストが捨てます。1 回に何行が届くかは
ホストが決めます。Spin 4.3.0 は 1 回に 1 行を渡すので、10,000 行で最初の行までが 43〜77 ms から 3.7 ms に、
最後の行までが 60〜100 ms から 260〜300 ms になり、100,000 行で Spin が使ったメモリは 105 MB から 11 MB に
なりました。

そのほかはドライバの
[README](https://github.com/making/rontolisp/blob/develop/jdbc-spin-postgres/README.md) にあります。
