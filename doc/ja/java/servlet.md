# Servlet アプリケーション

Jakarta Servlet API に対して書いたプログラムは、この[サブセット](index.md)がコンパイルできる
サーブレットコンテナの上で動かせば、どのターゲットでもサーバーになります。
[`servlet-httpserver`](https://github.com/making/rontolisp/tree/develop/servlet-httpserver)
がそのようなコンテナです。JDK 自身の HTTP サーバー（`com.sun.net.httpserver`）の上に作られて
おり、このサブセットはインタプリタ、JVM、WASM コンポーネント、ネイティブ実行ファイルのすべてで
その API を使えます（[HTTP](index.md#http)）。現在はこのリポジトリにあり、rontolisp には
依存していません。

コンテナのソースと API のソース jar をプログラムの[ソースパス](index.md#using-a-third-party-library)に
置くと、プログラムと一緒にコンパイルされます。

```console
public class Hello {

    static class HelloServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.setContentType("text/plain");
            response.getWriter().println("Hello, " + request.getParameter("name") + "!");
        }

    }

    public static void main(String[] args) throws Exception {
        ServletHttpServer server = new ServletHttpServer(8080);
        server.getServletContext().addServlet("hello", new HelloServlet()).addMapping("/hello");
        server.start();
    }

}
```

（import するのは `java.io.IOException`、`am.ik.servlet.httpserver.ServletHttpServer`、
`jakarta.servlet.http` の 3 つのクラスです）

```bash
LIBS="--java-source-path servlet-httpserver/src/main/java --java-source-dep jakarta.servlet:jakarta.servlet-api:6.1.0"
rontolisp Hello.java $LIBS                                     # interpreter
rontolisp Hello.java $LIBS -o Hello.class && java Hello        # JVM
rontolisp Hello.java $LIBS -o hello.wasm --component && wasmtime serve -S cli=y hello.wasm
rontolisp Hello.java $LIBS --native -o hello && ./hello        # native executable
```

`wasmtime serve` では、ホストが待ち受けます（`--addr`、既定は `0.0.0.0:8080`）。プログラムが
指定したポートには bind しません。プログラムが持つのは、API とコンテナのうち到達する部分だけです。
上のコンポーネントは約 950 KB で、そのうち API のコードは 8 KB です。

サーブレット、フィルター、リスナーは、`start()` の前に `getServletContext()` を通してコードで
登録します。このコンテナは `web.xml` もアノテーションも読みません。登録するクラスは API の型
そのものなので、同じサーブレットは他のコンテナでも動きます。他のコンテナでは、`web.xml`、
アノテーション、`ServletContainerInitializer` のいずれかで登録します。このコンテナが持つ機能
（マッピング、フィルター、forward と include、パラメーターと本文、バッファーした応答）と、拒否する
機能（セッション、非同期リクエスト、マルチパートの本文、セキュリティ、エラーページ、静的リソース）は
[README](https://github.com/making/rontolisp/blob/develop/servlet-httpserver/README.md) にあります。

## 差異

- **コンポーネントのリクエストには、ホスト名もクライアントのアドレスもありません。**
  `wasi:http` はプログラムに `Host` フィールドもクライアントのアドレスも渡さないので、
  `getServerName()` は `localhost`、`getServerPort()` は 80、`getRemoteAddr()` は `0.0.0.0` です。
  本文は、サーブレットが設定した長さにかかわらず chunked で送ります。
- **OPTIONS は、サーブレットのクラスが宣言するメソッドを問い合わせます**（`HttpServlet.doOptions`）。
  このサブセットはメソッドの名前で答えます（[言語との差異](deviations.md#what-differs)）。
- **まだコンパイルできないもの**があり、プログラムが到達した位置で名前を示して拒否されます。
  cookie（`jakarta.servlet.http.Cookie` は `Cloneable` で、このサブセットには `Cloneable` が
  ありません）と、リクエストのロケール（`getLocale()`、`getLocales()`。任意の言語の `Locale` が
  必要です）です。
