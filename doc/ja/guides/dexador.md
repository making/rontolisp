# HTTPクライアント（dexador）

[Dexador](https://github.com/fukamachi/dexador) — Common Lisp のコードの多くが
前提にしている HTTP クライアント — は `(ql:quickload "dexador")` で**無改変の
まま**ロードできます。ソケット層は組み込みの
[usocket シム](tcp-sockets.md#the-usocket-compatible-shim)、TLS 層は組み込みの
[`cl+ssl` シム](asdf-systems.md#built-in-shim-systems)で、それ以外の依存
(fast-http、quri、babel、chipz、cl-cookie、trivial-mimes など)は本物の
ライブラリです。リダイレクト、chunked と gzip のボディ、クッキー、
keep-alive、リクエストとレスポンスのエンコードはすべて dexador 自身の Lisp
コードが処理するので、リクエストは SBCL と同じように振る舞います。

ライブラリを使わずにリクエストを送るなら、組み込みのクライアント
[`rontolisp:fetch`](http-fetch.md) があります。dexador は、すでに dexador で
書かれたコードのためのものです。

## リクエスト

`dex:get`、`dex:post`、`dex:head`、`dex:put`、`dex:patch`、`dex:delete` は、
ボディ、ステータス、レスポンスヘッダ(小文字の名前をキーとするハッシュ
テーブル)の 3 つの値を返します:

```console
$ cat client.lisp
(ql:quickload "dexador" :silent t)
(multiple-value-bind (body status headers) (dex:get "http://127.0.0.1:8080/get?lang=lisp")
  (format t "~a ~a~%~a~%" status (gethash "content-type" headers) body))
(format t "~a~%" (dex:post "http://127.0.0.1:8080/post"
                           :content '(("name" . "rontolisp") ("city" . "Tōkyō"))))
$ rontolisp client.lisp
200 application/json
{"args":{"lang":"lisp"}, ...}
{"data":"name=rontolisp&city=T%C5%8Dky%C5%8D", ...}
```

`:content` の扱いは dexador のものです。文字列とオクテットベクタはそのまま
送られ、連想リストは URL エンコードしたフォームになります(値にパス名が
あれば `multipart/form-data`)。`:headers` は連想リストで、そこに
`Content-Type` を書けば既定値を上書きします。ステータスが 400 以上だと
`dex:http-request-failed`、またはステータスごとのサブタイプ(404 なら
`dex:http-request-not-found` など)が通知され、`dex:response-status`、
`dex:response-body`、`dex:response-headers` で応答を読めます:

```console
(handler-case (dex:get "http://127.0.0.1:8080/missing")
  (dex:http-request-not-found (e)
    (format t "~a ~a~%" (dex:response-status e) (dex:response-body e))))
```

リダイレクトは追従され(`:max-redirects`、既定値 5)、gzip のボディは展開
されます。`(cl-cookie:make-cookie-jar)` で作った `:cookie-jar` はサーバーが
設定したクッキーを保持し、接続はプールから取られるので、同じホストへの
連続したリクエストは 1 本のソケットを使い回します。`:want-stream t` は
ボディをストリームで返し、それを閉じると残りを読み捨てて接続をプールに
戻します。`:read-timeout`(秒、既定値 10)は実際の読み取り期限で、応答しない
サーバーへのリクエストは `read-byte: Read timed out` で失敗します。

[`examples/net/dexador-client.lisp`](https://github.com/making/rontolisp/tree/develop/examples/net/dexador-client.lisp)
はこれらのリクエストを
[`examples/net/httpbin.lisp`](https://github.com/making/rontolisp/tree/develop/examples/net/httpbin.lisp)
に対して送ります。

## HTTPS

`https://` の URL は [`rontolisp:tls-upgrade`](../reference/functions/rontolisp-tls-upgrade.md)
の上の `cl+ssl` シムを通り、サーバー証明書とホスト名が検証されます。
インタプリタと JVM ではトラストストアは JDK のものです。独自の CA を
信頼させるには `javax.net.ssl.trustStore` システムプロパティで自分の
トラストストアを指定します。`:insecure t`(または `dex:*not-verify-ssl*` を
`t` に束縛)は検証を省きます。開発用途に限ってください。

```console
(dex:get "https://example.com/")                   ; verified
(dex:get "https://127.0.0.1:8443/" :insecure t)    ; a self-signed development server
```

ハンドシェイクの失敗(信頼できない証明書、証明書にない名前)は、どの
バックエンドでも `error` を通知します。

## バックエンド

- **インタプリタ**と **JVM クラス** — 上記すべて。コンパイルしたクラスは
  実行時サポートクラスを隣の `am/` に書き出すので、両者を一緒に置いて
  ください(`-o client.jar` なら両方が入ります)。
- **WASM コンポーネント** (`--component`) — HTTP と HTTPS:

  ```bash
  rontolisp client.lisp -o client.wasm --component
  wasmtime run -S tcp=y -S inherit-network=y -S tls=y -S allow-ip-name-lookup=y client.wasm
  ```

  `http://` しか使わないプログラムでも `-S tls=y` が必要です(dexador は
  どちらの場合も TLS 層を参照します)。URL のホストが IPv4 リテラルでなく
  名前なら `-S allow-ip-name-lookup=y` も要ります。コンポーネントは IPv4
  でのみ接続します。証明書は wasmtime 自身のルートで検証されるので
  `javax.net.ssl` プロパティは効かず、`:insecure t` はエラーを通知します
  (ホストが検証を省く手段を持たないためです)。
- **ネイティブ実行ファイル** (`--native`) — HTTP のみ。`https://` の URL は
  リクエストの実行時にエラーを通知します。ビルドは通ります。
- **WASM Preview 1** — コンパイルは通り、最初のリクエストが `--native` を
  名指しするエラーを通知します。素の Preview 1 モジュールにはソケットが
  ありません。

dexador 自体はどのバックエンドでも `--dir` を必要としません(マルチパートで
送るファイルの読み取りには必要です)。dexador がロードする trivial-mimes は
プログラムの起動時に MIME テーブルを作ります。実行するマシンに
`/etc/mime.types` があればそれを、なければリリースに同梱された写しを読み、
その写しはコンパイルされたプログラムが持ち運びます。

## 現在の制限

- **プロキシ非対応。** `:proxy` と SOCKS5 は対応していません。
- **クライアント証明書非対応。** `:ssl-key-file`、`:ssl-cert-file`、
  `:ssl-key-password` はエラーを通知し、`:ca-path` も同様です。代わりに
  トラストストアのプロパティを使ってください。
- **ネイティブ実行ファイルと Preview 1 からは `https://` を使えません**
  (「バックエンド」を参照)。

関連: バックエンドごとのソケットと TLS の規則は [TCPソケット](tcp-sockets.md)、
組み込みクライアントは [HTTPリクエスト（fetch）](http-fetch.md)、ライブラリの
一覧は [システム（asdf）](asdf-systems.md) を参照してください。
