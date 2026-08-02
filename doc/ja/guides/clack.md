# Clack Web アプリケーション

[Clack](https://github.com/fukamachi/clack) — 深町英太郎さんによる Common Lisp
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
| `:request-method` | メソッドのキーワード (`:GET`、`:POST`、...) |
| `:script-name` | `""` |
| `:path-info` | リクエストパス |
| `:query-string` | 生のクエリ文字列、なければ `nil` |
| `:request-uri` | パス + `?` + クエリ |
| `:server-name` / `:server-port` | `clackup` の `:address` / `:port` から |
| `:server-protocol` | `:http/1.1` |
| `:url-scheme` | `"http"` |
| `:headers` | 小文字化したヘッダ名をキーとするハッシュテーブル (`:test 'equal`)。重複したリクエストヘッダは最後の値が残ります |
| `:content-type` / `:content-length` | 上のテーブルから (なければ `nil`) |
| `:raw-body` | リクエストボディの読み取り可能なストリーム (リクエストごとに事前ドレイン済み) |
| `:remote-addr` / `:remote-port` | `""` / `nil` — サーバがまだ運んでいません |

レスポンスの `body` は文字列のリスト・単一の文字列・
`(vector (unsigned-byte 8))` (各オクテットがそのコードポイントの文字になる)・
`nil` のいずれかです。pathname のボディ (静的ファイル) と関数のボディ
(ストリーミングレスポンダプロトコル) は明確なエラーを送出します。

## バックエンド

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
- **WASM Preview 1** は設計上着信 TCP を持ちません: プログラムはコンパイル
  でき、`clackup` は実行時に `HTTP-HANDLER requires --component ...` を送出
  します (`handler-case` で捕捉可能)。

## 現在の制限

- Clack サーバはプロセスあたり 1 つ: 2 つ目の同時 `clackup` は 1 つ目の
  アプリケーションを置き換えます。
- `clack.socket` (WebSocket) と `:swank-port` は未対応です (`:swank-port` は
  `swank` スタブに到達し、エラーを送出します)。
- レスポンスのストリーミング (関数ボディ) と pathname ボディは上記のとおり
  エラーを送出します。

関連: 土台となるサーバは [HTTP を Serve する (http-handler)](http-handler.md)、
バックエンドごとの実行コマンド付きデモは
[`examples/asdf/clack-hello.lisp`](https://github.com/making/rontolisp/blob/main/examples/asdf/clack-hello.lisp)
を参照してください。
