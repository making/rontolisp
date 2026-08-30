# rontolisp パッケージの関数

`rontolisp` パッケージは **Common Lispの一部ではない**
実装固有の関数を提供します。`rontolisp:` 修飾子で参照する(または `(in-package rontolisp)`
の後に修飾なしで)使用してください。パッケージシステムについては
[パッケージ](../packages.md) を参照してください。以下の各名前はそれぞれのページにリンクしています。

| Function | Example | Result |
|----------|---------|--------|
| `rontolisp:version` | `(rontolisp:version)` | ビルド情報のプロパティリスト(`:version`, `:build-timestamp`, `:git-commit`, `:git-branch`) |
| `rontolisp:random-bytes` | `(rontolisp:random-bytes 16)` | 暗号論的に強い乱数バイトのベクタ (`SecureRandom` / WASI `random_get`) |
| `rontolisp:make-mutex` | `(rontolisp:make-mutex)` | 新しい相互排他ロック。不透明なハンドル(インタプリタと JVM では実体があり、WASM では no-op) |
| `rontolisp:mutex-acquire` | `(rontolisp:mutex-acquire m)` | このスレッドが mutex を保持するまでブロックし、それを返します(通常は `rontolisp:with-mutex` を使用) |
| `rontolisp:mutex-release` | `(rontolisp:mutex-release m)` | mutex の獲得を 1 回分解放し、それを返します |
| `rontolisp:make-thread` | `(rontolisp:make-thread fn bindings)` | 引数なしの関数を実行する仮想スレッドを生成します。省略可能な `(symbol . value)` の動的束縛をその中に確立し、不透明なハンドルを返します(インタプリタと JVM。WASM のシムはエラーを通知) |
| `rontolisp:join-thread` | `(rontolisp:join-thread th)` | スレッドを待ってその関数の値を返します。スレッドが通知したエラーはここで再通知されます |
| `rontolisp:threadp` | `(rontolisp:threadp v)` | 値がスレッドハンドルなら `t` |
| `rontolisp:thread-alive-p` | `(rontolisp:thread-alive-p th)` | スレッドが実行中の間 `t`(join 後は `nil`) |
| `rontolisp:destroy-thread` | `(rontolisp:destroy-thread th)` | スレッドに割り込みをかけ、ハンドルを返します |
| `rontolisp:current-thread` | `(rontolisp:current-thread)` | 呼び出したスレッド自身のハンドル。スレッドごとに `eq` 安定です (`make-thread` で生成したスレッドに限らず任意のスレッドで動作します) |
| `rontolisp:fetch` | `(rontolisp:fetch "http://example.com/")` | HTTPリクエストを非同期に開始します。future を返します |
| `rontolisp:futurep` | `(rontolisp:futurep v)` | 値が future（`async-defun` で定義した関数の呼び出し、`rontolisp:fetch`、`rontolisp:stream-read` などが返す値）なら `t` |
| `rontolisp:streamp` | `(rontolisp:streamp v)` | 値が非同期ストリームなら `t`（ファイルストリームに答える `cl:streamp` とは別の述語） |
| `rontolisp:make-stream` | `(rontolisp:make-stream)` | 新しいオープン状態の非同期ストリームを作成します。1 つの値が読み側と書き側の両端を持ちます |
| `rontolisp:stream-read` | `(rontolisp:stream-read s)` | ストリームの次のチャンク（終端では `nil`）で確定する future |
| `rontolisp:stream-write` | `(rontolisp:stream-write s "chunk")` | チャンク（`nil` は不可）を追加します。ストリームが受け付けた時点で確定する future を返します |
| `rontolisp:stream-close` | `(rontolisp:stream-close s)` | 書き側をクローズします。バッファ済みチャンクは読み取り可能なままで、その後の read は終端を観測します |
| `rontolisp:read-all` | `(rontolisp:read-all s)` | 残りのチャンクを 1 つの文字列に読み切った値で確定する future (オクテットチャンク -- HTTP ボディストリームのもの -- は UTF-8 デコード) |
| `rontolisp:wait-for` | `(rontolisp:wait-for 100)` | 指定ミリ秒後に `nil` で確定する future。`cl:sleep` の非同期版の対応物 |
| `rontolisp:then` | `(rontolisp:then f (lambda (v) (* 2 v)))` | future に対する変換を値として付与します。成功チャネル上に新しい future を返します (JavaScript の `.then`) |
| `rontolisp:then*` | `(rontolisp:then* f #'1+ #'1+)` | `rontolisp:then` の可変長チェーン糖衣。各関数は 1 つ前の段の平坦化された値を受け取ります |
| `rontolisp:catch` | `(rontolisp:catch f (lambda (c) :fallback))` | future に対するエラー時フォールバックを値として付与します (JavaScript の `.catch`)。`cl:catch`/`throw` とは別物 |
| `rontolisp:finally` | `(rontolisp:finally f (lambda () (cleanup)))` | 成功・エラーどちらの経路でも走る後始末 thunk。元の結末はそのまま通過します |
| `rontolisp:http-handler` | `(rontolisp:http-handler 'handle 8080)` | Clack の環境 plist を受け取り `(status headers body)` を返すハンドラ関数でHTTPリクエストを処理します（ブロッキングサーバ。`--component` では `wasi:http` コンポーネント） |
| `rontolisp:json-parse` | `(rontolisp:json-parse "{\"n\": 1}")` | JSON文字列をパースします（jzon互換）: オブジェクトは文字列キーのハッシュテーブル、配列はベクタになります |
| `rontolisp:json-stringify` | `(rontolisp:json-stringify (vector 1 2))` | 値をJSON文字列にシリアライズします（ハッシュテーブルとCLOSインスタンスはオブジェクト、リストとベクタは配列） |
| `rontolisp:plist-hash-table` | `(rontolisp:plist-hash-table (list :n 1))` | プロパティリストからハッシュテーブルを構築します（`alexandria:plist-hash-table` のサブセット）。JSONオブジェクトに便利です |
| `rontolisp:hash-table-plist` | `(rontolisp:hash-table-plist h)` | ハッシュテーブルのペアのプロパティリスト（`alexandria:hash-table-plist` のサブセット） |
| `rontolisp:alist-hash-table` | `(rontolisp:alist-hash-table al)` | 連想リストからハッシュテーブルを構築します（`alexandria:alist-hash-table` のサブセット） |
| `rontolisp:hash-table-alist` | `(rontolisp:hash-table-alist h)` | ハッシュテーブルのペアの連想リスト（`alexandria:hash-table-alist` のサブセット） |
| `rontolisp:alist-plist` | `(rontolisp:alist-plist al)` | 連想リストのキー・値を順序を保ったままプロパティリストにします（`alexandria:alist-plist` のサブセット） |
| `rontolisp:plist-alist` | `(rontolisp:plist-alist pl)` | プロパティリストのキー・値を順序を保ったまま連想リストにします（`alexandria:plist-alist` のサブセット） |
| `rontolisp:tcp-connect` | `(rontolisp:tcp-connect "127.0.0.1" 7777)` | ブロッキングTCP接続を開きます。双方向ストリームハンドルを返します |
| `rontolisp:tcp-listen` | `(rontolisp:tcp-listen 7777)`, `(rontolisp:tcp-listen 0 "127.0.0.1")` | リスニングTCPソケットをバインドしてリスナーハンドルを返します。ポート `0` は空きエフェメラルポートを選びます |
| `rontolisp:tcp-accept` | `(rontolisp:tcp-accept listener)` | クライアント接続を待ちます (ブロッキング)。双方向ストリームハンドルを返します |
| `rontolisp:tcp-local-port` | `(rontolisp:tcp-local-port listener)` | リスナーまたはソケットが実際にバインドされているローカルポート |
| `rontolisp:tcp-local-address` | `(rontolisp:tcp-local-address listener)` | リスナーまたはソケットがバインドされているローカルIPアドレス（文字列） |
| `rontolisp:tcp-peer-address` | `(rontolisp:tcp-peer-address sock)` | 接続済みソケットのリモートIPアドレス（文字列） |
| `rontolisp:tcp-peer-port` | `(rontolisp:tcp-peer-port sock)` | 接続済みソケットのリモートポート |
| `rontolisp:tcp-set-timeout` | `(rontolisp:tcp-set-timeout sock 5000)` | 読み取りデッドラインをミリ秒で設定します(`nil` で解除)。タイムアウトした読み取りは捕捉可能なエラーを通知します |
| `rontolisp:tls-connect` | `(rontolisp:tls-connect "example.com" 443)` | 暗号化（TLS）クライアント接続を開きます。`tcp-connect` と同じ種類のストリームハンドルを返します |
| `rontolisp:tls-listen` | `(rontolisp:tls-listen "server.p12" "changeit" 8443)` | PKCS12キーストアから暗号化リスニングソケットをバインドします。`tcp-accept` で受け付けます |
| `rontolisp:tls-listen-pem` | `(rontolisp:tls-listen-pem "cert.pem" "key.pem" 8443)` | PEMの証明書／鍵ファイルから暗号化リスニングソケットをバインドします |
| `rontolisp:tls-upgrade` | `(rontolisp:tls-upgrade sock "example.com")` | 接続済みのストリームハンドルをクライアントとしてTLSでラップします。新しいストリームハンドルを返します |
| `rontolisp:wasm-export` | `(rontolisp:wasm-export 'fact :params '(:int) :returns :int)` | WASMコアモジュールへのコンパイル時に `defun` をホストから呼び出し可能にします |
| `rontolisp:jvm-export` | `(rontolisp:jvm-export 'fact :params '(:s64) :returns :s64)` | JVMクラスへのコンパイル時に、`defun` の型付きでJavaから呼び出し可能なstaticメソッドを宣言します |
| `rontolisp:wasm-import` | `(rontolisp:wasm-import 'add :from "host" :params '(:int :int) :returns :int)` | WASMコアモジュールへのコンパイル時に、ホスト関数をLispから呼び出し可能として宣言します |
| `rontolisp:wit-export` | `(rontolisp:wit-export "greeter.wit" :world greeter)` | プログラムがWIT worldを実装していることを宣言します。worldのエクスポートはプログラムの `defun` と照合され、型はWITから得られます |
| `rontolisp:wit-import` | `(rontolisp:wit-import "store.wit" :interface "wasi:keyvalue/store@0.2.0" :package kv)` | プログラムがWITインターフェースを呼び出すことを宣言します。宣言された各関数が通常のLisp関数（`kv:bucket-get`）として束縛され、インタプリタ／JVMではプロバイダに、Preview 1ではWASMインポートに、`--component` ではホストをプロバイダとする `canon lower` 済みのコンポーネントモデルインポートに向かいます |
| `rontolisp:wit-provide` | `(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'my-store)` | `wit-import` したインターフェースの実装をインタプリタとJVMバックエンドで束縛します（WASMではホストが供給するため無効化されます） |

`rontolisp:fetch`
は外向きのHTTPリクエストを開始して future を返し、`rontolisp:await` がそれを解決します。全体像は
[HTTPリクエストガイド](../../guides/http-fetch.md)を、オプション、結果plist、バックエンドのサポート、制限については
[fetch](rontolisp-fetch.md)、
[await](../special-forms/rontolisp-await.md)、
[futurep](rontolisp-futurep.md) のリファレンスページを参照してください。`rontolisp:http-handler` は `fetch` の受信側で、Clack の環境 plist と `(status headers body)` レスポンスリストを使ってハンドラ関数でHTTPリクエストを処理します。各バックエンドでの実例は
[HTTPサーバガイド](../../guides/http-handler.md)を、バックエンドのサポートと制限は
[http-handler](rontolisp-http-handler.md) のリファレンスページを参照してください。`rontolisp:json-parse` と `rontolisp:json-stringify` はJSONドキュメントとLispの値を相互変換します（`com.inuoe.jzon` 互換の軽量サブセット。fetchレスポンスボディのパースなどに使えます）。値の対応と制限については
[json-parse](rontolisp-json-parse.md) と
[json-stringify](rontolisp-json-stringify.md) のリファレンスページを参照してください。tcp関数（`rontolisp:tcp-connect` / `tcp-listen` / `tcp-accept` / `tcp-local-port` および[アドレスアクセサ](rontolisp-tcp-addresses.md)）は素のTCPソケットを開き、そのハンドルには標準のストリーム関数（`read-line` / `write-line` / `read-byte` / `write-byte` / `close`）がそのまま使えます。echoサーバーの実例は
[TCPソケットガイド](../../guides/tcp-sockets.md)を、バックエンドのサポートと制限は
[tcp-connect](rontolisp-tcp-connect.md)、
[tcp-listen](rontolisp-tcp-listen.md)、
[tcp-accept](rontolisp-tcp-accept.md)、
[tcp-local-port](rontolisp-tcp-local-port.md) のリファレンスページを参照してください。既存のCommon Lispコードとの互換のために、これらの上に[usocket互換シム](usocket.md)が用意されています。TLS版（`rontolisp:tls-connect` / `tls-upgrade` / `tls-listen` / `tls-listen-pem`）は同じストリームハンドルをTLSで包みます。
[tls-connect](rontolisp-tls-connect.md)、
[tls-upgrade](rontolisp-tls-upgrade.md)、
[tls-listen](rontolisp-tls-listen.md)、
[tls-listen-pem](rontolisp-tls-listen-pem.md) のリファレンスページを参照してください。`rontolisp:wasm-export`、`rontolisp:jvm-export`、`rontolisp:wasm-import`、`rontolisp:wit-export`、`rontolisp:wit-import`
はコンパイル時ディレクティブです。`jvm-export` は `wasm-export` のJVM版の双子 — コンパイルされたクラス上の型付きでJavaから呼び出し可能なエントリポイント — です（[jvm-export](rontolisp-jvm-export.md)）。WITの2つは `.wit` ファイルを境界の唯一の真実の源とするため、型を手書きすることはありません。`wit-export` はプログラムがWIT worldを**実装している**ことを宣言し（`--scaffold-wit` はそこから実装のスケルトンを生成します）、`wit-import` はWITインターフェースを**呼び出す**ことを宣言して、インターフェースが宣言する各関数を通常のLisp関数として束縛します。インタプリタとJVMバックエンドでは*プロバイダ*（[`rontolisp:wit-provide`](rontolisp-wit-provide.md)）へ、Preview 1 WASMでは `rontolisp:wasm-import` へ、`--component` では `canon lower` 済みのコンポーネントモデルのインスタンスインポートへローワリングされ（後者2つではホストがプロバイダになります）、1つのソースがすべてのバックエンドで動きます。rontolispは**どのインターフェースについてもプロバイダを同梱していません**。同梱しているのはプロバイダの仕組みであって、個々のインターフェースが何であるかは知らないため、WITインターフェースの実装は通常のLispコードです。WITの `result` のerrorアームは `rontolisp:wit-error` コンディションをシグナルし、そのペイロードは `rontolisp:wit-error-payload` で読みます。
[wasm-export](rontolisp-wasm-export.md)、
[wasm-import](rontolisp-wasm-import.md)、
[wit-export](rontolisp-wit-export.md)、
[wit-import](rontolisp-wit-import.md)、
[wit-provide](rontolisp-wit-provide.md) のリファレンスページ、および
[WebAssemblyへのコンパイル](../../compiling/wasm.md) ガイドを参照してください。

