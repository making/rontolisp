# WebMvc.fn アプリケーション

Spring MVC の
[Functional Endpoints](https://docs.spring.io/spring-framework/reference/web/webmvc-functional.html)
（WebMvc.fn）の書き方でルーティングを関数として書いたプログラムは、[サブセット](index.md)の
すべてのターゲットで動きます。Spring Framework そのものは、このサブセットではコンパイル
できません。アプリケーションコンテキストも `DispatcherServlet` も Jackson も、リフレクションを
前提にしているからです。代わりに
[`webmvc-fn-lite`](https://github.com/making/rontolisp/tree/develop/webmvc-fn-lite) を使います。
これは WebMvc.fn の一部を、Spring Framework と同じクラス名とシグネチャで、Servlet API だけの
上に実装したモジュールです。名前とシグネチャが同じなので、同じソースが Spring Framework に
対してもコンパイルできます。いまはこのリポジトリに置いてありますが、rontolisp には依存して
いません。Spring Framework そのものではなく、その開発元の製品でもありません。

ルーティングは次のように書きます。

```console
package com.example;

public class Routing {

    public RouterFunction<ServerResponse> routes() {
        return RouterFunctions.route()
            .GET("/", request -> ServerResponse.ok().body("Hello World!"))
            .GET("/hello/{name}", request -> {
                Map<String, Object> greeting = new LinkedHashMap<>();
                greeting.put("hello", request.pathVariable("name"));
                greeting.put("shout", request.param("shout").isPresent());
                return ServerResponse.ok().body(greeting);
            })
            .build();
    }

}
```

起動するクラスでは、[Servlet コンテナ](servlet.md)に `DispatcherServlet` を登録します。

```console
package com.example;

public class Application {

    public static void main(String[] args) throws Exception {
        DispatcherServlet dispatcherServlet = new DispatcherServlet(applicationContext(new Routing().routes()));
        ServletHttpServer.builder()
            .port(8080)
            .servlet("dispatcherServlet", dispatcherServlet, servlet -> servlet.urlPatterns("/"))
            .start();
    }

    static StaticWebApplicationContext applicationContext(RouterFunction<ServerResponse> routes) {
        StaticWebApplicationContext applicationContext = new StaticWebApplicationContext();
        applicationContext.registerBean(DispatcherServlet.HANDLER_MAPPING_BEAN_NAME, HandlerMapping.class, () -> {
            RouterFunctionMapping mapping = new RouterFunctionMapping(routes);
            mapping.setMessageConverters(List.of(new StringHttpMessageConverter(),
                    new ByteArrayHttpMessageConverter(), new JacksonJsonHttpMessageConverter()));
            return mapping;
        });
        return applicationContext;
    }

}
```

（import するのは Spring Framework のパッケージ `org.springframework.web.servlet.function`、
`org.springframework.web.servlet`、`org.springframework.web.context.support`、
`org.springframework.http.converter` のクラスと、`am.ik.servlet.httpserver.ServletHttpServer` です）

モジュールのソース jar は、コンテナのものと並べてプログラムの
[ソースパス](index.md#using-a-third-party-library)に加わります。どちらも Central の snapshot
リポジトリにある `-SNAPSHOT` で、`--java-snapshots` がそのリポジトリを加えます。

```bash
LIBS="--java-source-dep am.ik.servlet:servlet-httpserver:0.1.0-SNAPSHOT --java-source-dep am.ik.spring:webmvc-fn-lite:0.1.0-SNAPSHOT --java-snapshots"
rontolisp com/example/Application.java $LIBS                                  # interpreter
rontolisp com/example/Application.java $LIBS -o App.class && java App         # JVM
rontolisp com/example/Application.java $LIBS -o app.wasm --component && wasmtime serve app.wasm
rontolisp com/example/Application.java $LIBS --native -o app && ./app         # native executable
rontolisp com/example/Application.java $LIBS -o worker.wasm --no-wasi --emit-worker-entry   # a reactor: a Cloudflare Worker
```

```
$ curl 'localhost:8080/hello/Ann?shout'
{"hello":"Ann","shout":true}
```

## 使えるもの

ルート（`GET` から `OPTIONS` まで、`route`、`add`）、パスや述語の下に入れ子にしたルート
（`path`、`nest`）、フィルター（`filter`、`before`、`after`）、エラーハンドラー（`onError`）が
使えます。述語は、メソッド、パス、ヘッダー、パラメーターで判定するものと、それらを組み合わせる
`and`、`or`、`negate` です。パスのパターンには、パス変数、`**`、`{*rest}` が書けます。
リクエストからはパラメーター、ヘッダー、属性、ボディ（`body(Class)`）を読めます。レスポンスには
ステータス、ヘッダー、ボディを指定できます。使えるメンバーと使えないメンバーの一覧は、
モジュールの
[README](https://github.com/making/rontolisp/blob/develop/webmvc-fn-lite/README.md) にあります。
用意していないメンバーを使うと、コンパイル時に `cannot find symbol` になります。黙って無視される
ことはありません。

次の 3 つのクラスは、名前は Spring Framework と同じですが、できることは限られます。

- **`JacksonJsonHttpMessageConverter` は Jackson を使いません。** 読み書きできるのは JSON の値に
  対応するものだけです。`Map`、`Collection`、`String`、`Number`、`Boolean`、`null` と、名前で
  書き出す enum 定数です。ボディは `Map` か `List` として読みます（`request.body(Map.class)`）。
  レコードや Bean など、プログラムが定義したクラスのオブジェクトは、クラス名を示して拒否します。
  オブジェクトと JSON の対応付けにはリフレクションが要るからです。
- **`DispatcherServlet`** が呼び出すのはハンドラー関数だけです。コントローラーもビューも
  ありません。
- **`StaticWebApplicationContext`** は、渡されたオブジェクトを名前で保持するだけです。
  インジェクションはしません。

パス変数に正規表現を書いたパターン（`{id:[0-9]+}`）は、ルートを作る時点で拒否します。

## 同じソースを JDK で動かす

`javac` でこの 2 つのクラスをモジュールの jar に対してコンパイルすれば、ほかの
[Servlet アプリケーション](servlet.md)と同じように `servlet-httpserver` の上で動きます。
同じソースは、Spring Framework 自身の jar（`spring-webmvc` と、JSON のコンバーターが使う
Jackson）に対しても、変更なしでコンパイルできます。返すレスポンスも同じです。モジュールの
テストでは、1 つのアプリケーションを両方で動かし、すべてのレスポンスを比べています。
`servlet-httpserver` は JDK の上で Spring Framework も動かします。Spring Framework の
`ServletContainerInitializer` は `@HandlesTypes` でクラスを要求し、コンテナはそのクラスを
クラスパスから[渡します](servlet.md#annotations)。

[`examples/java/hello-mvcfn/`](https://github.com/making/rontolisp/tree/develop/examples/java/hello-mvcfn)
は、これを Maven プロジェクトで示します。`mvn -Pspring exec:exec` は、モジュールの代わりに
`spring-webmvc` に対して同じソースを動かします。

[`examples/java/mvcfn/`](https://github.com/making/rontolisp/tree/develop/examples/java/mvcfn)
は、この書き方の小さな JSON API です。入れ子のパス、パス変数、クエリーパラメーター、
リクエストボディ、フィルター、エラーハンドラーを使っています。4 つのターゲットと JDK で
動かす手順も、そこにあります。JSON は [tiny-json](https://github.com/making/tiny-json) の値で、
`JacksonJsonHttpMessageConverter` の代わりに、この例自身のコンバーター
（`AbstractHttpMessageConverter` のサブクラス）が読み書きします。
[`examples/java/shiori/`](https://github.com/making/rontolisp/tree/develop/examples/java/shiori)
は、この書き方で HTML のページを返す Web アプリケーションです。ハンドラーは jmustache の
テンプレートで作った文字列を返し、フォームは `params()` から YAVI のスキーマで読みます。
