# TCPソケット

`rontolisp` パッケージは素のTCPネットワーキングのための4つの関数と、両側の
暗号化版 (`tls-connect` と `tls-listen`) を提供します。
これらは **Common Lispの一部ではない** ため、`rontolisp:` 修飾子で参照します
([パッケージ](../reference/packages.md)を参照)。接続されたソケットはファイル
ストリームと同じハンドル空間の **双方向ストリームハンドル** なので、標準の
ストリーム関数がそのまま使えます: `read-line`、`write-line`、`write-string`、
`read-byte`、`write-byte`、`close`。バッファリングされるファイル出力と異なり、ソケットへの
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
> は通常のフラグに加えて `-W exceptions=y -S tcp=y -S inherit-network=y` を
> 付けて実行する必要があります (tcp componentは常にexception-handlingモードで
> コンパイルされます)。tcp関数と
> [`rontolisp:http-handler`](http-handler.md) の組み合わせは1つのcomponentに
> コンパイルでき、`wasmtime serve` で動きます — 上のフラグに `-S cli=y` を
> 追加してください (これがないとserveのリンカがインスタンス化時に
> `wasi:sockets@0.3.0` の `tcp-socket` リソースを missing と報告します)。
> wasmCloudの `wash dev` (2.5.2) もこのcomponentをホストできますが、
> `wasi:sockets` 0.3 を提供しないため、そこではtcp接続は失敗します —
> ハンドラが自らソケットを開く必要がある場合は `wasmtime serve`
> (またはインタプリタ / JVM) で動かしてください。**ブラウザプレイグラウンド** ではすべてのtcp関数がエラーを
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
([`examples/net/echo-server.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/echo-server.lisp)
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
wasmtime run -W gc=y -W exceptions=y -S tcp=y -S inherit-network=y echo-server.wasm
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
[`https-hello.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/https-hello.lisp)
と
[`kv-server-tls.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/kv-server-tls.lisp):

```console
(let* ((listener (rontolisp:tls-listen "tls-server.p12" "changeit" 8443))
       (sock (rontolisp:tcp-accept listener)))
  ...  ; serve the connection with the standard stream functions
  (close sock)
  (close listener))
```

## usocket 互換シム

既存の Common Lisp のネットワークコードは、処理系固有のソケット API では
なく [usocket](https://github.com/usocket/usocket) ポータビリティ
ライブラリに対して書かれていることがほとんどです。rontolisp は
`rontolisp:tcp-*` 組み込みの上でそのコア API を再現する組み込みの
`usocket` パッケージを備えているため、そうしたコードがより少ない変更で
動きます -- Postmodern の cl-postgres ソケット層
(`:element-type '(unsigned-byte 8)` 付きの `socket-connect` +
`socket-stream`)はそのまま動きます:

```lisp
(let* ((listener (usocket:socket-listen "127.0.0.1" usocket:*auto-port*))
       (port (usocket:get-local-port listener))
       (client (usocket:socket-connect "127.0.0.1" port :element-type '(unsigned-byte 8))))
  (write-line "hello" (usocket:socket-stream client))
  (let* ((server (usocket:socket-accept listener))
         (line (read-line (usocket:socket-stream server))))
    (usocket:socket-close server)
    (usocket:socket-close client)
    (usocket:socket-close listener)
    line)) ; => "hello"
```

rontolisp のソケットはストリームハンドルそのものなので、対応は直接的です:
`usocket:socket-stream` は恒等関数、`usocket:socket-close` は `close`、
`usocket:socket-listen` はホストが先の引数順を `rontolisp:tcp-listen` に
変換します。`usocket:*wildcard-host*`(`"0.0.0.0"`)と
`usocket:*auto-port*`(`0`)は usocket と同じように動き、`get-local-*` /
`get-peer-*` アクセサでポートとアドレスを読み戻せます。`with-*` 便利マクロ
([`with-client-socket` / `with-connected-socket` / `with-server-socket` /
`with-socket-listener`](../reference/macros/usocket-with-macros.md))は
本体の前後でソケットを束縛して閉じます。パッケージは最初の使用時に
ロードされ、組み込み ASDF システム `"usocket"` でもあります:
`(asdf:load-system "usocket")`、`(ql:quickload :usocket)`、サードパーティ
`.asd` の `:depends-on ("usocket")` はいずれもネットワークに触れずに
解決されます。

シムの制限(意図的なものです -- rontolisp のソケットモデルは lite です):

- **TCP のみ。** `:protocol :datagram`(UDP)はエラーを通知し、
  `socket-send` / `socket-receive` / `socket-shutdown` は存在しません。
- **インタープリタと JVM では型付きコンディション。**
  `socket-connect`/`socket-listen`/`socket-accept` の失敗は型付きの
  `usocket:socket-error` を通知します(メッセージは保持)。そのため
  `(handler-case (usocket:socket-connect ...) (usocket:socket-error (e) ...))`
  が動作します。サブタイプ(`connection-refused-error` など)も定義されますが、
  再通知は常に `socket-error` を使います(そちらを捕捉してください)。WASM
  コンポーネントバックエンドでは connect/accept の失敗はシグナルせず `nil`
  ハンドルを返すため、そこでは `handler-case` パターンには捕捉対象がありません
  (代わりにハンドルの `nil` を判定してください)。`wait-for-input` 的な
  コンディション処理は存在しません。
- **`wait-for-input` と `socket-server` は存在しません**(読み込みは
  ブロックします。accept ループは自分で書いてください)。
- **`with-*` マクロはインタープリタと JVM ではあらゆる脱出時にソケットを
  閉じます**([`unwind-protect`](../reference/special-forms/unwind-protect.md)
  に展開されます)。これは WASM コンポーネントバックエンドでも成り立ちます
  (tcp コンポーネントはもともと `-W exceptions=y` 付きで実行されます)。互換性の
  ためのキーワード引数(`:element-type`、`:timeout`、`:nodelay`、
  `:reuse-address` など)は受理して無視します。
- **バックエンド**: インタープリタと JVM はフル対応。WASM は tcp 組み込みと
  同じくコンポーネント専用で、アドレス系・peer 系アクセサはそこでも実際の
  アドレスとポートを返します(失敗時はエラーを通知せず `nil` を返します)。

## その他のサンプル

[`examples/` ディレクトリ](https://github.com/making/rontolisp/tree/develop/examples)
にはさらに多くのソケットプログラムがあり、それぞれのヘッダーコメントに
バックエンドごとの実行手順が書かれています:

- [`echo-client.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/echo-client.lisp)
  — echoサーバーに対応するクライアント: 標準入力の行をサーバーに送り、
  応答をそれぞれ表示します。サーバーとクライアントは *別々の* バックエンドで
  実行できます。
- [`http-hello.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/http-hello.lisp)
  — `curl` やブラウザが理解する最小のHTTP/1.1サーバー。ソケットハンドル上の
  `read-line`/`write-line` で構築されています (CRLFのリクエスト行はどの
  バックエンドでも普通の行として読めます)。TLS版の
  [`https-hello.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/https-hello.lisp)
  は同じページをHTTPSで提供します (`curl -k https://127.0.0.1:8443/`)。
- [`kv-server.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/kv-server.lisp)
  — ミニチュアの **Redis互換** インメモリkey-valueサーバー: 本物の
  `redis-cli` が動く程度のRESP2を話し (`PING`/`SET`/`GET`/`DEL`/`INCR`/...)、
  ハッシュテーブルの状態は接続をまたいで保持されます。TLS版の
  [`kv-server-tls.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/kv-server-tls.lisp)
  は同じプロトコルをTLSで提供します (`redis-cli --tls --insecure -p 6380`)。

HTTPについては、どちらの向きでもソケット上でプロトコルを手書きする必要は
ありません。*クライアント* 側は `rontolisp:fetch`
（[HTTPリクエストガイド](http-fetch.md)参照）、*サーバ* 側は
`rontolisp:http-handler` がリクエストのパースとレスポンスの変換を引き受けます
（[HTTP サーバガイド](http-handler.md)参照）。
