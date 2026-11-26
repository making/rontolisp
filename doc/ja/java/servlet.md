# Servlet アプリケーション

Jakarta Servlet API に対して書いたプログラムは、この[サブセット](index.md)がコンパイルできる
サーブレットコンテナの上で動かせば、どのターゲットでもサーバーになります。
[`servlet-httpserver`](https://github.com/making/rontolisp/tree/develop/servlet-httpserver)
がそのようなコンテナです。JDK 自身の HTTP サーバー（`com.sun.net.httpserver`）の上に作られて
おり、このサブセットはインタプリタ、JVM、WASM コンポーネント、ネイティブ実行ファイルのすべてで
その API を使えます（[HTTP](index.md#http)）。現在はこのリポジトリにあり、rontolisp には
依存していません。JDK の下では Java 21 が必要です。

コンテナと API のソース jar をプログラムの[ソースパス](index.md#using-a-third-party-library)に
加えると、プログラムと一緒にコンパイルされます。コンテナは Central の snapshot リポジトリにある
`-SNAPSHOT` で、`--java-snapshots` がそのリポジトリを加えます。

```console
public class Hello {

    @WebServlet("/hello")
    public static class HelloServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.setContentType("text/plain");
            response.getWriter().println("Hello, " + request.getParameter("name") + "!");
        }

    }

    public static void main(String[] args) throws Exception {
        ServletHttpServer.builder().port(8080).discover().start();
    }

}
```

（import するのは `java.io.IOException`、`am.ik.servlet.httpserver.ServletHttpServer`、
`jakarta.servlet.annotation.WebServlet`、`jakarta.servlet.http` の 3 つのクラスです）

```bash
LIBS="--java-source-dep am.ik.servlet:servlet-httpserver:0.1.0-SNAPSHOT --java-snapshots"
rontolisp Hello.java $LIBS                                     # interpreter
rontolisp Hello.java $LIBS -o Hello.class && java Hello        # JVM
rontolisp Hello.java $LIBS -o hello.wasm --component && wasmtime serve hello.wasm
rontolisp Hello.java $LIBS --native -o hello && ./hello        # native executable
rontolisp Hello.java $LIBS -o worker.wasm --no-wasi --emit-worker-entry   # a reactor: a Cloudflare Worker
```

`wasmtime serve` では、ホストが待ち受けます（`--addr`、既定は `0.0.0.0:8080`）。プログラムが
指定したポートには bind しません。プログラムが持つのは、API とコンテナのうち到達する部分だけです。
`--no-wasi` のモジュールは、リクエストごとにホストから呼び出され、同じくポートに bind しません
（[Cloudflare Worker](index.md#a-cloudflare-worker)）。

## サーバーの定義

`ServletHttpServer.builder()` は、最初にサーバーが待ち受ける場所（`port(int)` または
`address(InetSocketAddress)`）を受け取ります。残りはどれも省略でき、順序も自由です。`start()` は
アドレスに bind し、ビルダーに渡されたものを渡された順に登録し、アプリケーションを初期化して
リクエストを受け付けます。`build()` は代わりに定義を返します。定義は以後変わらず、`start()` を
呼ぶたびに新しいサーバーを起動します。

```console
ServletHttpServer server = ServletHttpServer.builder()
    .port(8080)
    .contextPath("/app")
    .servlet("hello", (request, response) -> response.getWriter().println("Hello!"),
            servlet -> servlet.urlPatterns("/hello"))
    .filter("log", (request, response, chain) -> {
        chain.doFilter(request, response);
        request.getServletContext().log(request.getRequestURI() + " " + response.getStatus());
    }, filter -> filter.urlPatterns("/*"))
    .onStartup(context -> context.log("started"))
    .sessions(sessions -> sessions.timeout(Duration.ofMinutes(30)).cookie(cookie -> cookie.name("SID")))
    .start();
server.port();                        // the bound port: port 0 binds a free one
server.stop(Duration.ofSeconds(5));   // waits for the requests in hand; stop() cuts them
```

- **サーブレット**は、インスタンス（`HttpServlet`）、引数のない public なコンストラクタで作る
  クラス、またはリクエストとレスポンスを受け取るラムダです。ラムダは HTTP メソッドを問わず、
  マッピングされたリクエストすべてに応答します。オプションは `urlPatterns`、`loadOnStartup`、
  `asyncSupported`、`initParameter`、`multipartConfig` です。
- **フィルター**は、インスタンス、クラス、またはリクエスト、レスポンス、チェーンを受け取る
  ラムダです。`chain.doFilter(request, response)` がリクエストを先へ渡します。オプションは
  `urlPatterns`、`servletNames`、`dispatcherTypes`（指定しなければ `REQUEST`）、
  `asyncSupported`、`initParameter` です。ビルダーのフィルターは、アプリケーションが宣言した
  フィルターより先に動きます。
- **リスナー**は、コンテキスト、リクエスト、セッションのいずれかのリスナーのインスタンスまたは
  クラスです。`onStartup(context -> ..)` と `onShutdown(context -> ..)` は、コンテキストの
  リスナーが受ける 2 つのイベントをラムダで書くものです。`onStartup` がコンテキストを通して
  登録したものは、アプリケーションに属します。
- `discover()` はアプリケーションが宣言したもの（[アノテーション](#annotations)）を、
  `documentRoot(path)` はリソースのディレクトリ（[静的リソース](#static-resources)）を、
  `sessions(..)` はストア、有効期間、追跡方法、cookie（[セッション](#sessions)）を、
  `executor(..)` はリクエストを実行するものを加えます（渡さなければリクエストごとに仮想
  スレッドを使います）。JDK 上では最大 1,024 接続が受理を待てます（OS の上限の範囲で）。
- `errorPage(..)` はエラーにアプリケーションのページで応答します（[エラーページ](#error-pages)）。
  `maxFormSize(bytes)` はパラメータとして読むフォームの上限です（指定しなければ 2 MiB、負の数で
  上限なし）。`log((message, thrown) -> ..)` は `ServletContext.log` とコンテナが書くものを
  受け取ります（指定しなければ標準エラー）。`https(sslContext)` は JDK の下で HTTPS を提供します。
- サーブレットやフィルターの名前を 2 回渡すか、2 つのサーブレットに同じ URL パターンを
  マッピングすると、`start()` が `ServletException` で失敗します。
- プログラムが持つのは、ビルダーのうち呼んだ部分だけです。`discover()` を呼ばないプログラムは
  検出の処理を持たず、`multipartConfig` を渡さないプログラムはパートを読む処理を持ちません。

## アノテーション

`discover()` は、ビルダーに渡されたものの後に、アプリケーションが宣言したものを登録します。
コンテナは `start()` で、`@WebServlet`、`@WebFilter`、`@WebListener`（と、その
`@WebInitParam`）が付いたクラスを見つけ、アノテーションのとおりにクラス名の順で登録します。
続けて、`META-INF/services/jakarta.servlet.ServletContainerInitializer` に書かれた
`ServletContainerInitializer` をすべて実行します。`@HandlesTypes` でクラスを要求する
イニシャライザーには、挙げた型を継承または実装するクラスと、挙げたアノテーションがクラス
自身、フィールド、コンストラクタ、メソッドのいずれかに付いたクラスを渡します。該当するクラスが
なければ `null` を渡します。クラスは `public` で、引数のない `public` のコンストラクタを持たなければ
なりません。`web.xml` は読みません。

クラスを探す場所は、プログラムを何が動かすかで変わります。コンテナのコードはどちらでも同じです。

- **JDK の上では**、コンテナはクラスパスを読みます。`java.class.path` にあるディレクトリと
  アーカイブのすべて、それらのマニフェストが `Class-Path` で挙げるアーカイブ（実行可能 jar が
  ライブラリを挙げる方法です）、プログラムのクラスローダーまたはその親である `URLClassLoader` の
  ディレクトリとアーカイブです。モジュールパスは読みません。プログラムは先にコンパイルしておきます
  （`javac`）。ソースランチャーに渡したファイル（`java Hello.java`）のクラスはクラスパスにないので、
  1 つも見つかりません。
- **rontolisp で実行またはコンパイルするプログラム**には、クラスパスがありません。アノテーションの
  付いたクラスは、プログラムを読み込むときに見つけます。プログラムのソースツリーと
  [ソースパス](index.md#using-a-third-party-library)のルートにあるクラスのうち、その
  アノテーションが付いたものすべてが対象で、プログラムが名前を挙げていないクラスも含みます。
  コンテナはそのクラス名を索引 `META-INF/annotations/<アノテーション型>` から 1 行に 1 つずつ
  読みます（[言語との差異](deviations.md#what-differs)）。ソースツリーは、プログラムのパッケージが
  始まるディレクトリです。そこにあるファイルにこれらのアノテーションが付いていれば、その
  クラスはプログラムの一部になります。`@HandlesTypes` でクラスを要求するイニシャライザーが
  あると、起動は失敗します。そのクラスはクラスファイルから探すものだからです。

クラスは API の型とアノテーションそのものなので、同じクラスを `web.xml` のない WAR として他の
コンテナにデプロイできます。このコンテナが持つ機能（マッピング、フィルター、forward と include、
パラメーターと本文、バッファーした応答、セッション、非同期リクエスト、マルチパートの本文、
静的リソース、エラーページ）と、拒否する機能（セキュリティ、トレーラー、プロトコルの
アップグレード）は
[README](https://github.com/making/rontolisp/blob/develop/servlet-httpserver/README.md) にあります。

[`examples/java/guestbook/`](https://github.com/making/rontolisp/tree/develop/examples/java/guestbook)
は複数のサーブレットからなるアプリケーションです。フィルター1つ、サーブレット3つ、リスナー1つを
アノテーションで見つけ、フォーム、リダイレクト、エラー応答、JSON API を持ちます。4つのターゲット、
JDK、Tomcat 上の WAR での動かし方も載せています。

[`examples/java/shiori/`](https://github.com/making/rontolisp/tree/develop/examples/java/shiori)
は PostgreSQL を使う Web アプリケーション一式です。読書記録のアプリケーションで、
[`examples/db/shiori`](https://github.com/making/rontolisp/tree/develop/examples/db/shiori) の
Java 版です。[WebMvc.fn](webmvc-fn.md)、[JDBC](jdbc.md)、jmustache のテンプレート、YAVI での
フォームの検証、tiny-json の上に書いています。ソケットを開くプログラムをコンポーネントとして
`wasmtime serve` で動かすには、`-S cli=y` と `-S inherit-network=y` が要ります。その理由と、4つの
ターゲットでの応答と速さは README にあります。

## エラーページ

`errorPage(status, location)` はステータスに、`errorPage(ExceptionType.class, location)` は
サーブレットやフィルターが投げた例外に応答します。例外には、そのクラスに最も近いクラスの
ページ、なければ `ServletException` の原因のページ、なければ 500 のページを使います。
`errorPage(location)` はそれ以外のエラーすべてに応答します。location はコンテキスト内の
パスで、スラッシュで始めます。

```console
ServletHttpServer.builder()
    .port(8080)
    .errorPage(404, "/not-found")
    .errorPage(IllegalArgumentException.class, "/bad-request")
    .errorPage("/error")
```

`sendError` を呼ぶと応答はコミット済みになり（`isCommitted()` が true）、ページはサーブレットが
戻った後に書かれます。コンテナはページへ `ERROR` ディスパッチで渡します（`ERROR` に
マッピングしたフィルターが動きます）。メソッドはリクエストのメソッドにかかわらず `GET`、
ステータスはページが別の値を設定しない限り要求されたもので、それまでに設定したフィールドは
残ります。リクエストは `RequestDispatcher` の属性でエラーを伝えます。`ERROR_STATUS_CODE`、
`ERROR_MESSAGE`、`ERROR_EXCEPTION` と `ERROR_EXCEPTION_TYPE`（`ServletException` の原因）、
`ERROR_REQUEST_URI`、`ERROR_SERVLET_NAME`、`ERROR_QUERY_STRING`、`ERROR_METHOD` です。
ページがなければコンテナ自身のページを書きます。応答のコミット後に投げられた例外は、JDK の
下では接続を切るので、クライアントは本文が欠けたことを知ります。

## セッション

`request.getSession()` は、クライアントが cookie（`JSESSIONID`、`HttpOnly`、パスはコンテキストの
パス）で保持するセッションを作ります。`changeSessionId()`、`invalidate()`、有効期間、セッションの
リスナーは API のとおりに動きます。セッションは、プログラムがビルダーに渡したストア
（`sessions(sessions -> sessions.store(store))`、`SessionStore`）に置きます。渡さなければ、クラスパスか
ソースパスにある `META-INF/services/am.ik.servlet.httpserver.SessionStore` が最初に挙げるストアに
置き、それもなければサーバーのメモリに置きます。メモリ上のセッションは、そのメモリを持つものが
続く間だけ残ります。

- **インタープリター、JVM、ネイティブ実行ファイル**: プロセスが続く間です。
- **`wasmtime serve` 上のコンポーネント**: インスタンスが続く間で、インスタンスはホストが
  入れ替えます。ホストが 1 つのインスタンスに渡すリクエストは最大 128 個です
  （`--max-instance-reuse-count`）。同時に来たリクエストは複数のインスタンスに分かれ、
  それぞれが別のセッションを持ちます。ホストは 1 秒アイドルだったインスタンスを破棄し
  （`--idle-instance-timeout`）、セッションも一緒に失われます。`--idle-instance-timeout 60s`
  とすれば、リクエストが 1 分に 1 回以上来る間はセッションが残ります。したがってセッションは、
  順に来る一連のリクエストの間は残りますが、複数のリクエストを同時に送るブラウザーには
  向きません。
- **`--no-wasi` のリアクター（Cloudflare Worker）**: インスタンスが続く間です。インスタンスは、
  ホストが保持している間（Worker の isolate が続く間）残り、呼び出しが失敗すると入れ替わります。
  セッション ID は `SecureRandom` で生成するため、ビルドには `--host-random` が必要です。

### `wasi:keyvalue` に置くセッション

[`servlet-httpserver-keyvalue`](https://github.com/making/rontolisp/tree/develop/servlet-httpserver-keyvalue)
はセッションをキーバリューストアに置きます。コンテナの代わりにソースパスに置くと（コンテナと
[`rontolisp-java-api`](wit.md) はこのライブラリが連れてきます）、プログラムにコードを書かなくても、
サーバーはこのストアを使います。

```bash
LIBS="--java-source-dep am.ik.servlet:servlet-httpserver-keyvalue:0.1.0-SNAPSHOT --java-snapshots"
rontolisp Hello.java $LIBS -o hello.wasm --component
```

コンポーネントでは、ストアはホストの `wasi:keyvalue/store@0.2.0-draft2` です。セッションは
インスタンスより長く残り、ホストがストアをディスクに置くならホストの再起動の後も残ります。
JDK、インタープリター、JVM では、ストアはメモリ上にあり、プロセスが続く間だけ残ります。

- **`wasi:keyvalue@0.2.0-draft2` を持つホスト**:
  [sluice-wicket-runtime-wasi](https://github.com/making/sluice/tree/main/sluice-wicket-runtime-wasi)
  はストアを SQLite のファイルに置きます（`--listen 127.0.0.1:8080 --wasm 'hello.local=hello.wasm?wasi=keyvalue'`）。
  `wasmtime serve -S keyvalue=y` が提供するのは `0.2.0-draft` だけで、このコンポーネントは起動時に
  拒否されます（`component imports instance wasi:keyvalue/store@0.2.0-draft2, but a matching
  implementation was not found`）。ライブラリを使わなければ、コンポーネントはこれまでどおり
  そこで動き、セッションはメモリに置きます。
- **Cloudflare Worker**: `--no-wasi` のモジュールはストアを canonical ABI でインポートし、
  `--emit-js-glue` が書き出すグルーが、`KEYVALUE` として束縛した Workers KV の名前空間の上で
  それに答えます
  （[Workers KV で答える `wasi:keyvalue`](../guides/wasm-host-boundary.md#wasikeyvalue-over-workers-kv)）。
  Workers KV は 1 つのキーへの書き込みを 1 秒に 1 回しか受け付けないので、リクエストの書き込みは
  応答を返した後、キーごとに 1 回、1 秒に 1 回まで書きます。リクエストが待つのは KV の読み出しだけです。
  ある拠点で書いたセッションがほかの拠点で見えるのは、最大 1 分後です。前のリクエストの書き込みが
  終わる前に送ったリクエストは、書き込み前のセッションを読むことがあります。
  [`hello-session-one-source`](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/hello-session-one-source)
  がその例です。

  ```bash
  rontolisp HelloSession.java $LIBS -o src/worker.wasm --no-wasi --host-random --emit-worker-entry
  ```
- **ネイティブ実行ファイル**は WIT をインポートできません。ライブラリを使うプログラムは
  コンパイル時に拒否されます。
- **属性の値は決まった型に限ります**: `String`、`Boolean`、`Character`、`Byte`、`Short`、
  `Integer`、`Long`、`Float`、`Double`、`byte[]`、およびそれらの `List`、`Set`、`Map`
  （読み戻すと `ArrayList`、`LinkedHashSet`、`LinkedHashMap`）です。ほかの値を `setAttribute` すると
  `IllegalArgumentException` を投げ、セッションは元のままです。Java のシリアライズは使いません。
- **セッションは変わるたびに全体を書きます**。`setAttribute` のたびに、またアクセス時刻が前に
  書いたときからセッションの有効期間の 10 分の 1（最大 1 分）進んだリクエストで、デフォルトの
  ストア（`""`）のキー `servlet-httpserver:session:<id>` に書きます。セッションを読むだけの
  リクエストはそれより頻繁には書かないので、セッションはその分早く期限切れになることがあり、
  `getLastAccessedTime()` もその分古い時刻を返すことがあります。値をその場で変えただけでは、
  もう一度 set するまで書きません。1 つのセッションに同時に来た 2 つのリクエストでは、後に書いた
  ほうが残ります。
- **期限切れのセッション**は、全インスタンスを通じて、ストアのキーを列挙して少しずつ探します。
  1 回に読むセッションは 16 個までで、続きは 1 分後に探し、すべてのキーを見終えたら次の一巡は
  10 分後に始めます。期限切れを見つけたインスタンスがセッションを無効にし、リスナーに知らせます。

## 非同期リクエスト

`asyncSupported` を付けて登録したサーブレット（`@WebServlet(urlPatterns = "/..", asyncSupported = true)`）は、
その前に並ぶフィルターもすべて `asyncSupported` なら、`request.startAsync()` を呼べます。応答は
その後、任意のスレッドからの `AsyncContext.complete()`、`AsyncContext.dispatch(..)`
（`DispatcherType.ASYNC` にマッピングしたフィルターが動きます）、またはタイムアウト（`setTimeout`
を呼ばなければ 30 秒）で終わります。`AsyncContext.start(task)` に渡したタスクは、サーバーの
executor で実行されます。コンテナは完了、タイムアウト、エラーを `AsyncListener` に通知し、
`ReadListener` と `WriteListener` を呼び出します。

- **非同期モードのリクエストは、スレッドを占有しません。** `startAsync` を呼んだディスパッチが
  戻ると、そのスレッドは JDK の上でも rontolisp のどのターゲットでも、ほかのリクエストの処理に
  進みます。その後にコンテナが行う処理（ディスパッチ、リスナーの呼び出し、タイムアウトや
  エラーへの応答、レスポンスの終了）は、求められた時点で executor のスレッドが実行します。
  求めるのは `complete()`、`dispatch(..)`、タイムアウトです。タイムアウトは、サーバーの
  スレッド 1 つが待ちます（タイムアウトを持つ最初のリクエストが作ります）。別のスレッドが
  報告したエラーとサーバーの停止は、報告されたスレッドで実行します。
- **ディスパッチが戻ったあと、コミット済みのレスポンスが失敗した場合は、そのまま終わります。**
  コンテナがこのようなレスポンスを途中で切れるのは、サーバーがハンドラーを呼んだスレッドが
  まだ戻っていない間だけです。`com.sun.net.httpserver` には、それ以外にエクスチェンジを
  切る手段がないからです。
- **ストリームの読み書きはブロックします。** `isReady()` は常に true を返します。コンテナが
  `onDataAvailable` と `onWritePossible` を再び呼ぶのは `isReady()` が false を返した後だけなので、
  どちらも 1 回だけ呼ばれ、その中の読み込みはクライアントを待つことがあります。
- **`wasmtime serve` では**、待っているリクエストの `AsyncContext` を別のリクエストから完了
  できるのは、ホストが両方を同じインスタンスに渡したときだけです。`AsyncContext` はその
  インスタンスのメモリにあるからです。同時に来たリクエストは複数のインスタンスに分かれる
  ことがあります（[セッション](#sessions)と同じです）。

## マルチパートの本文

マルチパートの設定（`@MultipartConfig`、またはオプションの `multipartConfig(..)`）を持つサーブレットは、
`multipart/form-data` の本文を `request.getParts()` と `getPart(name)` で読みます。ファイル名の
ないパートはパラメーターにもなります。パートは設定の `fileSizeThreshold` まではメモリに、それを
超えるとファイルに置きます。ファイルは `location` のディレクトリ（絶対パスならそのまま、そうで
なければ `java.io.tmpdir` の下）に作り、リクエストの完了時に削除します。残すには
`part.write(name)` を呼びます。

- **rontolisp で実行またはコンパイルしたプログラムには `java.io.tmpdir` がありません。**
  そのため `location` が絶対パスのディレクトリでない限り、パートはメモリに置きます
  （コンポーネントのホストは書き込めるディレクトリを渡さないことがあります）。メモリに置く量は
  `maxFileSize` と `maxRequestSize` で制限します。
- **制限を超えると** `getParts()` と `getParameter(..)` が `IllegalStateException` を投げます。
  RFC 7578 が認めない本文（`Content-Disposition: form-data` と名前のないパート、閉じる区切りの
  ない本文など）では、`getParts()` が `IOException` を、`getParameter(..)` が
  `IllegalStateException` を投げます。
- **フィールドの値**は、そのパートの `Content-Type` の charset、なければフィールド `_charset_`
  が指定する charset、なければリクエストの charset、なければ UTF-8 で読みます。

## 静的リソース

プログラムのリソースのうち `META-INF/resources` の下にあるファイル（Maven のプロジェクトなら
リソースのディレクトリの下にあるもの）が静的リソースです。プログラムが `/` に自分の
サーブレットをマッピングしていなければ、`default` という名前のサーブレットがそれを `/` で返します。
`META-INF/resources/index.html` は `/` のページ、`META-INF/resources/css/site.css` は
`/css/site.css` のページです。`getServletContext().getResource(..)`、`getResourceAsStream(..)`、
`getResourcePaths(..)` もこれらのファイルを返します。

- **rontolisp で実行またはコンパイルするプログラム**では、ファイルはソースツリーとソースパスに
  あるもので、コンパイル時に見つけてプログラムに組み込みます（[リソース](index.md#resources)）。
  そのため `wasmtime serve` で動かすコンポーネントも、ファイルシステムなしで返せます。
  `Last-Modified` が示すファイルの更新時刻は、サーバーを作った時刻です。プログラムに組み込んだ
  ファイルはそれ以後変わりえないからです。デフォルトサーブレットは実行時に組み立てた名前で
  ファイルを探すので、プログラムはソースパスの各ディレクトリのファイルをすべて持ちます（jar と
  ソースツリーでは `META-INF/resources` の下のものだけです）。ソースツリーにもソースパスにも
  `META-INF/resources` がなければ、プログラムはデフォルトサーブレットをまったく持ちません。
- **JDK の上では**クラスパスにあるファイルで、更新時刻はそれぞれのファイルのものです。
- **ビルダーの `documentRoot(path)`** は、先に探すディレクトリを加え
  ます。ディレクトリはプログラムが動く場所で読みます。JDK、インタープリター、JVM、ネイティブ
  実行ファイルで使え、ファイルシステムのないコンポーネントでは使えません。

デフォルトサーブレットは、GET と HEAD にファイルの長さ、メディアタイプ、更新時刻を付けて
応答します。更新時刻を条件とするリクエスト（`If-Modified-Since` など）と、1 つのバイト範囲の
指定（`Range`）にも応じます。ディレクトリにはその `index.html` か `index.htm` を返し、末尾の
スラッシュなしで要求されたディレクトリにはリダイレクトを返します。`WEB-INF` と `META-INF` は
返しません。WAR としてデプロイすると、Tomcat も Jetty も `WEB-INF/classes/META-INF/resources`
のファイルは返しません。両者が返すのは `WEB-INF/lib` にあるアーカイブの `META-INF/resources`
です。

## 差異

- **コンポーネントのリクエストには、ホスト名もクライアントのアドレスもありません。**
  `wasi:http` はプログラムに `Host` フィールドもクライアントのアドレスも渡さないので、
  `getServerName()` は `localhost`、`getServerPort()` は 80、`getRemoteAddr()` は `0.0.0.0` です。
  本文は、サーブレットが設定した長さにかかわらず chunked で送ります。
- **HTTPS には JDK が要ります。** このサブセットには TLS のサーバーがないので、
  `https(..)` を呼ぶプログラムは `javax.net.ssl.SSLContext` を名指す位置で拒否されます。
- **コミット後の例外**は、JDK の下では接続を切ります。4 つのターゲットでは、応答はその時点の
  まま終わります。
- **OPTIONS は、サーブレットのクラスが宣言するメソッドを問い合わせます**（`HttpServlet.doOptions`）。
  このサブセットはメソッドの名前で答えます（[言語との差異](deviations.md#what-differs)）。
- **リクエストのロケール**（`getLocale()`、`getLocales()`）は任意の言語のロケールです。
  ロケールが書くものはサブセットのものです（[言語との差異](deviations.md#a-locale-of-any-language)）。
