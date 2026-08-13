# Clack Web アプリケーション

[Clack](https://github.com/fukamachi/clack) — Common Lisp
の Web アプリケーション環境 — は `(ql:quickload "clack")` で無改変のまま
ロードでき、`clack:clackup` は組み込みの `clack-handler-rontolisp`
バックエンドで Clack アプリケーションを実行します:

```console
$ cat app.lisp
(ql:quickload "clack")
(clack:clackup
 (lambda (env)
   (list 200 '(:content-type "text/plain")
         (list (format nil "Hello, Clack! ~A ~A~%"
                       (getf env :request-method) (getf env :path-info)))))
 :server :rontolisp
 :port 5000
 :use-thread nil)
$ rontolisp app.lisp        # interpret; or -o App.class / -o app.wasm --component
$ curl http://127.0.0.1:5000/hello
Hello, Clack! GET /hello
```

この裏に変換レイヤはありません: rontolisp 自身のサーバプロトコルが Clack の
プロトコル*そのもの*なので（[HTTP サーバ](http-handler.md)を参照）、
バックエンドはアプリケーションをハンドラとしてサーバに渡すだけで、
リクエストごとの変換を一切行いません — Clack アプリケーションはそのまま
有効な `rontolisp:http-handler` ハンドラであり、その逆も成り立ちます。

初回実行時に clack、[lack](https://github.com/fukamachi/lack) とその依存が
`~/.rontolisp/quicklisp` にダウンロードされます。依存は実ライブラリ
(alexandria、ironclad スライス) と
[組み込みシムシステム](asdf-systems.md#built-in-shim-systems)
(bordeaux-threads、usocket、swank、uiop) に解決されます。

## clackup のセマンティクス

デフォルト値は Clack ユーザーの期待どおりに動きます:

- **`:use-thread t` (デフォルト)** はサーバがバックグラウンドスレッド
  ([`rontolisp:make-thread`](../reference/functions/rontolisp-make-thread.md))
  で応答している間にハンドラオブジェクトを返し、`(clack:stop handler)` が
  そのサーバを停止します。
- **`:use-thread nil`** はフォアグラウンドで serve します: `clackup` は
  プロセスが停止される (Ctrl-C) までブロックします — 上のスクリプト形です。
- **`:use-default-middlewares t` (デフォルト)** は `lack:builder` を通じて
  アプリケーションを lack のバックトレースミドルウェアでラップします。
- `:address` はリスナーのバインド先 (デフォルト `127.0.0.1`)。`:silent t` は
  バナーを、`:debug nil` はデバッグ通知を抑制します。

## アプリケーションプロトコル

アプリケーションは、標準の Clack env plist を受け取り標準の
`(status headers body)` リストを返す関数です:

| env キー | 値 |
|---------|-------|
| `:request-method` | メソッドの大文字化・intern 済みキーワード (`:GET`、`:POST`、...) |
| `:script-name` | `""` |
| `:path-info` | パーセントデコード済みのリクエストパス |
| `:query-string` | 生のクエリ文字列、なければ `nil` |
| `:request-uri` | 生のリクエストターゲットそのまま (エンコードされたまま、クエリ込み) |
| `:server-name` / `:server-port` | `Host` ヘッダがあればそこから、なければリスナーの値 |
| `:server-protocol` | キーワード。例: `:HTTP/1.1` |
| `:url-scheme` | `"http"` または `"https"` |
| `:headers` | 小文字化したヘッダ名をキーとするハッシュテーブル (`:test 'equal`)。重複したリクエストヘッダはワイヤ順に `", "` で結合されます |
| `:content-type` / `:content-length` | 上のテーブルから (なければ `nil`。`:content-length` は整数) |
| `:raw-body` | リクエストボディの同期・インメモリな bivalent ストリーム — `read-line`/`read-char` と `read-byte`/`read-sequence` の両方が動き、本物の `file-position` を持ちます (lack-request と http-body が必要とする形)。ボディの無いリクエストでは `nil` |
| `:remote-addr` / `:remote-port` | インタープリタと JVM では実際のピア。WASI コンポーネントでは `nil` (`wasi:http@0.3.0` はピアのアクセサを公開しません) |

レスポンスの `body` は文字列のリスト・
`(vector (unsigned-byte 8))` (各オクテットがそのコードポイントの文字になる)・
rontolisp のストリーム・`nil` のいずれかで、2 要素の `(status headers)` 形も
有効です。裸の文字列は明確なエラーを送出します。Clack 自身も
文字列を拒否します。pathname のボディ (lack のファイル配信形) はここでは独立した
値であり、トランスポートが配信できるようになるまでこちらも拒否されます。関数のボディは Clack の delayed レスポンス形
(responder が最終的なレスポンスリストで呼ばれる形) に対応し、streaming
writer 形はエラーを送出します。

## 1 つのハンドラからルートの集合へ

上のアプリケーションはサイト全体で 1 つの関数です。それをルートの集合にするのが
ルーティングライブラリで、[tiny-routes](https://github.com/jeko2000/tiny-routes)
は無改変でロードできます ([ASDF システムガイド](asdf-systems.md)を参照):

```console
$ cat routes.lisp
(ql:quickload "clack")
(ql:quickload "tiny-routes")

(defpackage :demo (:use :cl :tiny-routes))
(in-package :demo)

(define-routes *app*
  (define-get "/hello" () (ok "hello world"))
  (define-get "/users/:id" (req) (ok (format nil "user ~A" (path-parameter req :id))))
  (define-post "/echo" (req) (ok (format nil "echo:~A" (request-body req))))
  (define-any "*" () (not-found "nope")))

(clack:clackup (pipe *app* (wrap-request-body) (wrap-query-parameters))
               :server :rontolisp :port 5000 :use-thread nil)
$ rontolisp routes.lisp
$ curl http://127.0.0.1:5000/hello
hello world
$ curl http://127.0.0.1:5000/users/42
user 42
$ curl -XPOST -d abc http://127.0.0.1:5000/echo
echo:abc
$ curl -o /dev/null -w '%{http_code}\n' http://127.0.0.1:5000/zzz
404
```

このライブラリのリクエストは上の env プリストそのもの、レスポンスはレスポンス
リストそのものなので、境界で何も変換されません: `wrap-request-body` は
`:raw-body` ストリームを読み、`wrap-query-parameters` は `:query-string` を
解析し、パステンプレートは `:path-info` に対して照合し、`ok`/`not-found` は
`(status headers body)` を組み立てます。ルートはアプリケーション自身のパッケージ
の中で読まれます — このライブラリが本来使われる場所です。

同じルートはサーバなしで全バックエンドで動きます — 自分で組み立てたリクエスト
プリストで合成済みハンドラを呼ぶだけで、
[`examples/asdf/tiny-routes-demo.lisp`](https://github.com/making/rontolisp/blob/develop/examples/asdf/tiny-routes-demo.lisp)
がそれをしています。serve する場合は下記のバックエンド制約が付きます。

### もう 1 つの答え: ningle

[ningle](https://github.com/fukamachi/ningle) も無改変でロードでき、こちらは
書き方の違いではなくモデルそのものが異なります。アプリケーションはルートを
ぶら下げる CLOS の**オブジェクト**で、各ルートは `setf`、コントローラは環境では
なくマッチした**パラメータ**を受け取り (リクエスト自体はスペシャル変数にあり
ます)、そもそも関数でないコントローラはそのままボディとして返されます:

```console
$ cat ningle-app.lisp
(ql:quickload "clack")
(ql:quickload "ningle")

(defpackage :demo (:use :cl))
(in-package :demo)

(defvar *app* (make-instance 'ningle:app))

(setf (ningle:route *app* "/") "Welcome to ningle!")
(setf (ningle:route *app* "/hello/:name")
      (lambda (params) (format nil "Hello, ~A" (cdr (assoc :name params)))))
(setf (ningle:route *app* "/submit" :method :POST)
      (lambda (params) (format nil "posted ~A" (cdr (assoc "q" params :test #'string=)))))

(clack:clackup *app* :server :rontolisp :port 5000 :use-thread nil)
$ rontolisp ningle-app.lisp
$ curl http://127.0.0.1:5000/
Welcome to ningle!
$ curl http://127.0.0.1:5000/hello/Eitaro
Hello, Eitaro
$ curl -XPOST -d q=abc http://127.0.0.1:5000/submit
posted abc
$ curl -o /dev/null -w '%{http_code}\n' http://127.0.0.1:5000/zzz
404
```

どちらを選ぶかの前に知っておく価値のある違いが 4 つあります:

- **ルートは列挙するのではなく追加します。** `(setf (ningle:route ...))` は
  アプリケーションを破壊的に変更するので、ルートはどこからでも — 実行時の
  データからでも — 追加できます。
- **クエリとボディのパラメータはテンプレートの `:name` 束縛と同じ alist に
  入ります** (キーは文字列名)。ningle が毎リクエストを `lack-request` 経由で
  読むためです。tiny-routes はこのチェーンに一切触れず、コンパイル済みモジュール
  のサイズ差はほぼそこから来ます — 同じ 2 ルートで一桁違い、しかも ningle の
  ルータは全ルールをスキャナにコンパイルするため、ppcre なしのオプトインという
  逃げ道もありません。
- **404 はキャッチオールのルートではなくメソッド** `ningle:not-found` です。
  また `ningle:*response*` は変更可能で、コントローラが 200 以外のステータスを
  返すのはこの経路です。
- **パス以外の条件でルートを選べます。** `:accept` ネゴシエーションは組み込み
  で、`(setf (ningle:requirement app :key) fn)` で独自の条件を登録できます。
  そのクロージャは毎回のディスパッチで実行されます。

## バックエンド

`:server :rontolisp` の行はどのバックエンドでも変わりません — 「このターゲット
本来の着信トランスポートで serve する」という意味で、選択はコンパイル時に
行われます:

- **インタプリタ** — 上記のすべて。
- **JVM クラス** — 同じプログラムを `-o App.class` でコンパイルします。serve
  するプログラムの常として実行時クラスパスに rontolisp の jar が必要です
  (`java -cp rontolisp-exec.jar:. App`)。
- **WASM コンポーネント** (`--component`) — ソケットはホストが所有します:
  `wasmtime serve -W gc=y -W exceptions=y -S cli=y -S tcp=y
  -S inherit-network=y app.wasm` で実行します。`:port` 引数は無視され、
  `:use-thread` は実質 `nil` (WASM バックエンドはシングルスレッドなので、
  そこではデフォルトが `nil`)、`clack:stop` は意味を持ちません — サーバの
  ライフサイクルはホストが制御します。
- **WASM リアクタ** (`--no-wasi` または `--no-gc`) — ホストが
  ソケットを渡す代わりにモジュールを**呼び出します**: 同じプログラムが
  `handle-request` (JSON のリクエスト文字列を受け取り JSON のレスポンス文字列を
  返す)をエクスポートするモジュールにコンパイルされ、Cloudflare Workers、
  ブラウザのページ、node、JVM ホストがリクエストごとにそれを呼びます。`:port`
  は無視され、`clackup` は即座に戻ります — 詳細は次のセクションで。
- **WASM Preview 1** は設計上着信 TCP を持ちません: プログラムはコンパイル
  でき、`clackup` は実行時に `HTTP-HANDLER requires --component ...` を送出
  します (`handler-case` で捕捉可能)。

## ホストから呼ばれる場合: リアクタビルド

ソケットを渡してこないホストもあります。Cloudflare Workers、ブラウザのページ、
node、JVM への埋め込みは、いずれもホスト側でリクエストを解析し、**エクスポート
された関数を呼び出します**。そこでは `clackup` に起動するものがありません —
それでも書くのは `clackup` であり、`:server :rontolisp` がターゲットごとに
トランスポートを選ぶので、ソースは*何も*変える必要がありません。まったく同じ
プログラムを `--no-wasi` でコンパイルすれば、ハンドラバックエンドがリアクタの
形を取ります。

```console
$ rontolisp app.lisp -o worker.wasm --no-wasi --optimize=size
```

ここでの `run` は何も起動しません。アプリケーションを保存するだけで、ホストが
呼ぶエクスポート(`handle-request` — JSON のリクエスト文字列を受け取り JSON の
レスポンス文字列を返す)は、ハンドラバックエンドが残したマーカーからコンパイラが
合成します。ソース側でその名前に触れる箇所はなく、モジュールは何もインポート
しません — JavaScript 側に WASI シムは不要です。

1 つのキーワードは*他の*バックエンドの性質であって、定型句ではありません:
`:use-thread nil` — インタプリタと JVM では `clackup` はバックエンドを別スレッド
で実行するのが既定ですが、スクリプトはフォアグラウンドで serve したいからです。
`clackup` のデフォルトミドルウェアはどこでも有効のままです: lack の `backtrace`
ミドルウェアはレポートを `*error-output*` に書きますが、これは `--no-wasi` では
破棄されるシンクで、それ以外のバックエンドでは本物の標準エラー出力です。

### fetch するハンドラ: `--host-fetch`

リアクタは何もインポートしません。それは HTTP クライアントも持たないという
ことでもあるので、[`rontolisp:fetch`](../reference/functions/rontolisp-fetch.md)
を呼ぶアプリケーション(プロキシ、API ゲートウェイ)にはもう 1 つフラグが必要
です。`--host-fetch` は `fetch` をホスト自身のクライアントへ `env.fetch` という
インポート 1 つとして落とします。上のモジュールとの違いはその 1 インポート
だけです:

```console
$ rontolisp worker.lisp -o worker.wasm --no-wasi --host-fetch --optimize=size
```

ルート本体は同期的なままで構いません。`await` できるのは `async-defun` /
`async-lambda` の本体だけなので、fetch した値が必要なルートはそれを呼び、その
**future** をそのまま返します — リアクタのトランスポートが future 値の
レスポンスを境界で解決します。`--component` で `wasmtime serve` が行うのと
同じです。このトランスポートに固有の残りの点(eager な `:body`、確定済みの
future、JavaScript 側の JSPI の義務)は
[fetch ガイド](http-fetch.md#fetching-from-a-reactor---no-wasi---host-fetch)に
あり、
[`examples/cloudflare-workers/dog-fetcher`](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/dog-fetcher)
がこの形で作られたルーティング付き Worker です — インタプリタと JVM では
ソケットを serve する、ひとつのソースです。

### リアクタを手で駆動する: `clack-handler-reactor`

2 つ目の組み込みハンドラバックエンドは、リアクタの形を**明示的に、すべての
バックエンドでホスト駆動に**します:

```console
$ cat worker.lisp
(ql:quickload "clack-handler-reactor")
(load "app.lisp")                       ; defines app, an ordinary Clack application

(clack:clackup #'app :server :reactor :use-thread nil)
```

`:rontolisp` がインタプリタと JVM でソケットを bind するのに対し、この
designator はそこでもアプリケーションを保存し、ホストは合成されたエクスポートが
呼ぶのと同じ関数 `(clack.handler.reactor:dispatch request-json)` を直接呼びます。
Worker なしで Worker を開発・テストできるのはこれによります: 編集・実行ループ
全体がインタプリタ上で回ります。Worker 自体にこの designator はもう必須では
ありません。両者は同じ機構と同じアプリケーション格納を使うので、乖離しようが
ありません。

その下にあるのが `handle` で、こちらは `clackup` も Worker も要りません。
2 引数の普通の関数です:

```lisp
(ql:quickload "clack-handler-reactor")

(defun app (env)
  (list 200 '(:content-type "text/plain")
        (list (format nil "~a ~a ~a" (getf env :request-method)
                      (getf env :path-info) (getf env :query-string)))))

(princ (clack.handler.reactor:handle
        #'app "{\"method\":\"GET\",\"target\":\"/hi?a=1\"}"))
```

```text
{"status":200,"headers":[["content-type","text/plain"]],"body":"GET /hi a=1"}
```

`handle` はアプリケーションと JSON のリクエスト文字列 1 つを受け取り、JSON の
レスポンス文字列 1 つを返します。Clack 環境の構築と Clack レスポンスの正規化は
serve 時とまったく同じ経路を通るので、アプリケーションからは Clack が約束する
ものがそのまま見えます。また `handle` は**エラーを捕捉します**: この種のホスト
では未捕捉のエラーはインスタンス全体を落とすため、代わりにコンディションの
report を載せた 500 を返します。

エンベロープは両方向とも次の形です:

```json
{ "method": "GET", "target": "/path?a=1", "headers": {"host": "..."},
  "body": "", "scheme": "https", "remote-addr": "203.0.113.7" }
```

```json
{ "status": 200, "headers": [["content-type", "text/plain"]], "body": "..." }
```

ホスト側で間違えやすい点が 2 つあります:

- `target` は**生の**リクエストターゲットです — パスとクエリは繋がったまま、
  パーセントエンコードされたままにします。分割とデコードは Lisp 側で行われ、
  アプリケーションが Clack の約束どおりのものを見るには `:path-info` /
  `:query-string` がそこから来る必要があります。
- ボディのあるリクエストでは `content-length` を送ってください。これがないと
  `lack/request` は何も解析せず、chunked で届いたリクエストにはそもそも付いて
  いません — 実際に読んだバイト数から設定してください。

レスポンスヘッダはオブジェクトではなく**ペアの配列**として渡されます。これに
より、Cookie を 2 つ設定するアプリケーションは `Set-Cookie` を 2 本返せます。

### ボディを分けて渡す

上の JSON はリクエストの**ヘッド**です。`handle` と `dispatch` はもう 1 つ
省略可能な引数、**ボディソース**を取るので、ボディをその中に載せる必要は
ありません:

- `nil` — ボディなし;
- 文字列 — 読み込み済みのボディ;
- 引数なしの関数 — **プルソース**: 呼ぶたびに次のチャンク — 文字列、または
  生のオクテットを渡すホストなら `(unsigned-byte 8)` のベクタ — を返し、
  終端では `nil` または空のチャンクを返します。future を返してもよいので、
  読み込み中にサスペンドするホストもそのまま渡せます。

チャンクの境界は UTF-8 のシーケンスの途中に落ちることがあります。ソケットを
読むホストはコードポイントのことを何も知らないからです。途中で切れた
シーケンスは次のチャンクへ持ち越され、2 つの不正な文字にはなりません。

**最初の**呼び出しで空を返すソースはボディなしそのものです — `:raw-body` は
`"body"` のないリクエストとまったく同じく `nil` のままになります。上流の
`(when raw-body ...)` はそれを前提にしており、ボディのない `GET` が中身の
ないストリームの代金を払う理由もありません。

エンベロープの `"body"` キーはちょうどこの文字列のケースであり、ソースを
渡さなかったとき — およびソースが空だったとき — に使われます。ホストは
エンベロープを埋めるのをやめないまま、リーダを渡し始めることができます。
上の形に合わせて書かれたホストはそのまま動きます。

### WASM のバウンダリ: ヘッドのエクスポートとボディのインポート

`--no-wasi` の WASM モジュールでは、ソースはホストが渡せる Lisp
の値ではありません。そこでバウンダリは 2 つのエントリになり、2
つめはコンパイラが書きます:

```text
module -> host   handle-request(headPtr, headLen) -> (ptr, len)   ; the JSON head
host -> module   env.readRequestBody(ptr, cap) -> n               ; up to cap octets
                                                                  ;   at ptr; 0 = end
```

ヘッドは上の JSON から `"body"` キーを**除いた**ものです。ボディは生のオクテットとして、
モジュールが持ち回す 1 つのバッファへ渡ります。これは JSON
文字列にはできなかったことです。**バイナリ**のボディがそのまま届き(文字列のバウンダリは
UTF-8 としてデコードし、しかも検証しません)、大きなアップロードがモジュールのリニアメモリを
まったく消費しません — エンベロープはボディを何重にも抱えていました。

インポートは `:async t`
で宣言されるので、どう答えるかはホストが選びます。オクテットを同期的に返す(先にボディを読んでから呼び込む)のが単純なホストで、Worker
のサンプルはこれです。インポートを `WebAssembly.Suspending`
で包んでリクエスト自身のリーダから引くのがストリーミングのホストで、その場合は
`handle-request` を `WebAssembly.promising`
で入り、呼び出しを直列化しなければなりません。サスペンドしたモジュールは再入されうるからです
— モジュールは両方の呼び出しを壊す代わりにトラップで拒否し、ビルドがその義務を表示します。

`--component` ではボディはエンベロープの中に留まります。コンポーネントのホスト関数はコアの
インポートではなく canonical ABI
を通るからです。このセクションより上はどちらでも変わりません。ボディソースが抽象的な値であることの意味がそれです。

アプリケーションが実際に見る形は `:raw-body` モードで決まります。`clackup` と
`handle` は Clack が約束する同期ストリーム（バッファ済み）を要求します
（ソースがどの形であってもそこへドレインされます）。素の
`rontolisp:http-handler` から作られたリアクタは**そのディレクティブの**
デフォルト、つまり rontolisp のストリームを保ち、他のバックエンドと同じ形で
ドレインできます:

```lisp
(rontolisp:async-defun handle (env)
  (let ((body (rontolisp:await (rontolisp:read-all (getf env :raw-body)))))
    (list 200 '(:content-type "text/plain") (list body))))
```

この方法で作った完全な Worker — JavaScript 側と実測値を添えたもの — は
[`examples/cloudflare-workers/httpbin-clack/`](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/httpbin-clack)
にあります。その隣の
[`examples/cloudflare-workers/httpbin-clack-one-source/`](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/httpbin-clack-one-source)
は
[`examples/net/httpbin-clack.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/httpbin-clack.lisp)
*そのもの* — インタプリタで実行すればソケットを bind するあのファイル — を
デプロイするので、ディレクトリには Lisp ファイルが 1 つもありません:
1 つのソースで 4 つのホストです。

`clackup` の 1 行よりモジュールサイズが重要なら、このアダプタは手書きできる程度
の量なので、clack のロード自体を省けます。
[`examples/cloudflare-workers/httpbin/`](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/httpbin)
がそれです。同じアプリケーション、同じエンベロープ、同じ JavaScript 側で、
モジュールは約半分になります。2 つのディレクトリは実測用のペアであり、
リクエストあたりのコストは同じで、このようなホストで clack が要求するのは
モジュールサイズとアイソレートの起動時間だけだと分かります。

## 現在の制限

- Clack サーバはプロセスあたり 1 つ: 2 つ目の同時 `clackup` は 1 つ目の
  アプリケーションを置き換えます。
- `clack.socket` (WebSocket) と `:swank-port` は未対応です (`:swank-port` は
  `swank` スタブに到達し、エラーを送出します)。
- streaming writer 形のレスポンスと裸の文字列／pathname のボディは上記の
  とおりエラーを送出します (delayed 形の関数レスポンスは動作します)。

関連: 土台となるサーバは [HTTP を Serve する (http-handler)](http-handler.md)、
バックエンドごとの実行コマンド付きデモは
[`examples/asdf/clack-hello.lisp`](https://github.com/making/rontolisp/blob/develop/examples/asdf/clack-hello.lisp)
を参照してください。
