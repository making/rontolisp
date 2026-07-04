# TCPソケット

`rontolisp` パッケージは素のTCPネットワーキングのための4つの関数と、両側の
暗号化版 (`tls-connect` と `tls-listen`) を提供します。
これらは **Common Lispの一部ではない** ため、`rontolisp:` 修飾子で参照します
([パッケージ](../reference/packages.md)を参照)。接続されたソケットはファイル
ストリームと同じハンドル空間の **双方向ストリームハンドル** なので、標準の
ストリーム関数がそのまま使えます: `read-line`、`write-line`、`read-byte`、
`write-byte`、`close`。バッファリングされるファイル出力と異なり、ソケットへの
書き込みは即時に送信され (`write-line` は行ごとにフラッシュ)、相手が接続を
閉じると `read-line` は `nil` を返します。

| 関数 | 用途 |
|----------|---------|
| [`rontolisp:tcp-connect`](../reference/functions/rontolisp-tcp-connect.md) | クライアント接続を開く: `(rontolisp:tcp-connect host port)` |
| [`rontolisp:tcp-listen`](../reference/functions/rontolisp-tcp-listen.md) | リスニングソケットをバインドする: `(rontolisp:tcp-listen port &optional host)` |
| [`rontolisp:tcp-accept`](../reference/functions/rontolisp-tcp-accept.md) | クライアント接続を待つ: `(rontolisp:tcp-accept listener)` |
| [`rontolisp:tcp-local-port`](../reference/functions/rontolisp-tcp-local-port.md) | バインドされたポートを読み取る (ポート `0` でlistenした後に便利) |
| [`rontolisp:tls-connect`](../reference/functions/rontolisp-tls-connect.md) | **暗号化された**クライアント接続を開く: `(rontolisp:tls-connect host port)` |
| [`rontolisp:tls-listen`](../reference/functions/rontolisp-tls-listen.md) | PKCS12 キーストアから**暗号化された**リスニングソケットをバインドする: `(rontolisp:tls-listen keystore password port &optional host)` |
| [`rontolisp:tls-listen-pem`](../reference/functions/rontolisp-tls-listen-pem.md) | PEM ファイルから**暗号化された**リスニングソケットをバインドする: `(rontolisp:tls-listen-pem cert-file key-file port &optional host)` |

> **バックエンドのサポート。** インタプリタとJVMコンパイル済みクラスはJDKの
> ソケットクラスを使い、ホスト名とIPリテラルの両方を受け付けます。WASM
> バックエンドは **componentモード専用** です (`--component`、
> `wasi:sockets@0.3.0` 経由): tcp関数はPreview 1 (コアモジュール) モードでは
> コンパイルエラーになり、ホストはIPv4リテラルでなければならず、component
> は非同期フラグに加えて `-S tcp=y -S inherit-network=y` を付けて実行する
> 必要があります。**ブラウザプレイグラウンド** ではすべてのtcp関数がエラーを
> シグナルします (ブラウザのサンドボックスには素のTCPがありません) — 下の
> 実行可能な例はブラウザの外でのみ動作します。共通の制限 (TCPのみ、
> UDPなし) については
> [tcp-connect](../reference/functions/rontolisp-tcp-connect.md)
> のリファレンスページを参照してください。
> TLS関数
> ([`rontolisp:tls-connect`](../reference/functions/rontolisp-tls-connect.md)、
> [`rontolisp:tls-listen`](../reference/functions/rontolisp-tls-listen.md)、
> [`rontolisp:tls-listen-pem`](../reference/functions/rontolisp-tls-listen-pem.md))
> はインタプリタ/JVM専用です (WASMバックエンドではコンパイルエラー)。

## 最初の往復

以下のスニペットは自己完結しています: エフェメラルポートでlistenし、
ループバックインターフェイス経由で自分自身に接続し、acceptしたハンドルを
通して1行をエコーバックします:

```lisp
(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
       (port (rontolisp:tcp-local-port listener))
       (sock (rontolisp:tcp-connect "127.0.0.1" port)))
  (write-line "ping" sock)
  (let* ((peer (rontolisp:tcp-accept listener))
         (line (read-line peer)))
    (write-line line peer)
    (let ((reply (read-line sock)))
      (close peer)
      (close sock)
      (close listener)
      reply)))   ; => "ping"
```

## echoサーバー

実際のサーバーは固定ポートをバインドし、acceptループで接続を処理します。
以下を `echo-server.lisp` として保存してください
([`examples/echo-server.lisp`](https://github.com/making/rontolisp/blob/develop/examples/echo-server.lisp)
としても同梱されています)。acceptしたハンドルは `read-line` が `nil` を返す
まで (クライアントが切断するまで) 1行ずつ読まれ、各行はそのまま書き戻され
ます:

```console
(let ((listener (rontolisp:tcp-listen 7777)))
  (if listener
      (progn
        (write-line "echo server listening on 127.0.0.1:7777")
        (do ((n 1 (+ n 1))) (nil)
          (let ((sock (rontolisp:tcp-accept listener)))
            (write-line (format nil "client ~a connected" n))
            (do ((line (read-line sock) (read-line sock)))
                ((null line) (close sock) (write-line "client disconnected"))
              (write-line line sock)))))
      (write-line "tcp-listen failed (is port 7777 already in use?)")))
```

`(if listener ...)` のチェックはWASM componentバックエンドで重要です。
そこではバインドの失敗はエラーをシグナルする代わりに `nil` を返します
(インタプリタとJVMはシグナルします)。サーバーは永久にループします —
`Ctrl-C` で停止してください。

### 実行方法

インタプリタで:

```bash
rontolisp echo-server.lisp
```

JVMクラスにコンパイルして (クラス名は出力ファイル名から付きます):

```bash
rontolisp echo-server.lisp -o EchoServer.class
java EchoServer
```

WASM componentにコンパイルして (wasmtime 46+。ネットワークアクセスを許可する
2つの `-S` フラグに注意 — これらがなくてもcomponentは起動しますが、
`tcp-listen` が `nil` を返します):

```bash
rontolisp echo-server.lisp -o echo-server.wasm --component
wasmtime run -W gc=y -W component-model-async=y \
  -W component-model-async-stackful=y -W component-model-more-async-builtins=y \
  -S tcp=y -S inherit-network=y echo-server.wasm
```

どのバックエンドでサーブしていても、任意のTCPクライアント、たとえば
`nc` (netcat) で会話できます:

```console
$ nc 127.0.0.1 7777
hello
hello
world
world
```

## TLS接続

[`rontolisp:tls-connect`](../reference/functions/rontolisp-tls-connect.md) は
`tcp-connect` の暗号化版です: 接続後に TLS ハンドシェイクを行い、同じ種類の
ストリームハンドルを返すため、`read-line`、`write-line`、`read-byte`、
`write-byte`、`close` がそのまま使えます。サーバー証明書は JDK デフォルトの
トラストストアで検証され、ホスト名も検証されます。自己署名証明書を受け入れる
には `javax.net.ssl.trustStore` システムプロパティで独自のトラストストアを
指定するか、`:insecure t` を渡して検証を完全にスキップします(開発用途のみ)。
詳細と手書き HTTPS の例はリファレンスページを参照してください:

```console
(let ((sock (rontolisp:tls-connect "example.com" 443)))
  ...  ; speak any TLS-wrapped protocol over the handle
  (close sock))
```

*サーバー*側は
[`rontolisp:tls-listen`](../reference/functions/rontolisp-tls-listen.md) です:
PKCS12 キーストアファイルを受け取り(自己署名キーストアを生成する 1 行の
`keytool` コマンドはリファレンスページに記載)、プレーンな
`rontolisp:tcp-accept` / `rontolisp:tcp-local-port` / `close` がそのまま使える
リスナーを返します。accept された各接続は最初の読み取りでハンドシェイクを
完了します。PKCS12 キーストアの代わりに PEM ファイル(certbot / OpenSSL の
出力)から直接提供するには、
[`rontolisp:tls-listen-pem`](../reference/functions/rontolisp-tls-listen-pem.md)
を使ってください。以下のサーバーの TLS 版は `examples/` ディレクトリにあります —
[`https-hello.lisp`](https://github.com/making/rontolisp/blob/develop/examples/https-hello.lisp)
と
[`kv-server-tls.lisp`](https://github.com/making/rontolisp/blob/develop/examples/kv-server-tls.lisp):

```console
(let* ((listener (rontolisp:tls-listen "tls-server.p12" "changeit" 8443))
       (sock (rontolisp:tcp-accept listener)))
  ...  ; serve the connection with the standard stream functions
  (close sock)
  (close listener))
```

## `http-handler` で HTTP を提供する

`read-line`/`write-line` で HTTP を手書きする（`http-hello.lisp` のような）方法も
勉強になりますが、素朴なリクエスト/レスポンス型のサーバであれば
[`rontolisp:http-handler`](../reference/functions/rontolisp-http-handler.md)
がパースを引き受けてくれます。ハンドラはリクエストのプロパティリスト
（`:method` / `:path` / `:headers` / `:body`）を受け取り、レスポンスのプロパティリスト
（`:status` / `:headers` / `:body`）を返します。これは
[`rontolisp:fetch`](http-fetch.md) と同じ値モデルで、送信ではなく受信側です。

```console
(defun handle (request)
  (list :status 200
        :headers (list (cons "content-type" "text/plain"))
        :body (format nil "Hello from rontolisp!~%~a ~a~%"
                      (getf request :method) (getf request :path))))

(rontolisp:http-handler 'handle 8080)
```

インタープリタではこれがブロックし、ポート 8080 で提供します（リクエストごとに
1 つの仮想スレッド）。

```console
$ java -jar rontolisp.jar app.lisp
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

同じソースは **WASI HTTP コンポーネント** にもコンパイルでき、`wasmtime serve` で
動作します。

```console
$ java -jar rontolisp.jar app.lisp -o app.wasm --component
$ wasmtime serve -W gc=y -W component-model-async=y \
    -W component-model-async-stackful=y -W component-model-more-async-builtins=y \
    app.wasm
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

この場合モジュールは `wasi:http/incoming-handler` をエクスポートし、ソケットはホストが
所有するため `port` 引数は無視されます。JVM バックエンドは開発中です。Spin
（`spin up`）ではまだ動作しません。組み込み wasmtime が、rontolisp のすべての
コンポーネントが必要とする WebAssembly GC プロポーザルを有効化していないためです。

## その他のサンプル

[`examples/` ディレクトリ](https://github.com/making/rontolisp/tree/develop/examples)
にはさらに多くのソケットプログラムがあり、それぞれのヘッダーコメントに
バックエンドごとの実行手順が書かれています:

- [`echo-client.lisp`](https://github.com/making/rontolisp/blob/develop/examples/echo-client.lisp)
  — echoサーバーに対応するクライアント: 標準入力の行をサーバーに送り、
  応答をそれぞれ表示します。サーバーとクライアントは *別々の* バックエンドで
  実行できます。
- [`http-hello.lisp`](https://github.com/making/rontolisp/blob/develop/examples/http-hello.lisp)
  — `curl` やブラウザが理解する最小のHTTP/1.1サーバー。ソケットハンドル上の
  `read-line`/`write-line` で構築されています (CRLFのリクエスト行はどの
  バックエンドでも普通の行として読めます)。TLS版の
  [`https-hello.lisp`](https://github.com/making/rontolisp/blob/develop/examples/https-hello.lisp)
  は同じページをHTTPSで提供します (`curl -k https://127.0.0.1:8443/`)。
- [`kv-server.lisp`](https://github.com/making/rontolisp/blob/develop/examples/kv-server.lisp)
  — ミニチュアの **Redis互換** インメモリkey-valueサーバー: 本物の
  `redis-cli` が動く程度のRESP2を話し (`PING`/`SET`/`GET`/`DEL`/`INCR`/...)、
  ハッシュテーブルの状態は接続をまたいで保持されます。TLS版の
  [`kv-server-tls.lisp`](https://github.com/making/rontolisp/blob/develop/examples/kv-server-tls.lisp)
  は同じプロトコルをTLSで提供します (`redis-cli --tls --insecure -p 6380`)。

HTTPの *クライアント* 側については、ソケット上でプロトコルを手書きする
必要はありません — `rontolisp:fetch` を使ってください。
[HTTPリクエストガイド](http-fetch.md)を参照してください。
