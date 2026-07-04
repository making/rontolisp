# TCPソケット

`rontolisp` パッケージは素のTCPネットワーキングのための4つの関数を提供します。
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

> **バックエンドのサポート。** インタプリタとJVMコンパイル済みクラスはJDKの
> ソケットクラスを使い、ホスト名とIPリテラルの両方を受け付けます。WASM
> バックエンドは **componentモード専用** です (`--component`、
> `wasi:sockets@0.3.0` 経由): tcp関数はPreview 1 (コアモジュール) モードでは
> コンパイルエラーになり、ホストはIPv4リテラルでなければならず、component
> は非同期フラグに加えて `-S tcp=y -S inherit-network=y` を付けて実行する
> 必要があります。**ブラウザプレイグラウンド** ではすべてのtcp関数がエラーを
> シグナルします (ブラウザのサンドボックスには素のTCPがありません) — 下の
> 実行可能な例はブラウザの外でのみ動作します。共通の制限 (TCPのみ、TLSなし、
> UDPなし) については
> [tcp-connect](../reference/functions/rontolisp-tcp-connect.md)
> のリファレンスページを参照してください。

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
  バックエンドでも普通の行として読めます)。
- [`kv-server.lisp`](https://github.com/making/rontolisp/blob/develop/examples/kv-server.lisp)
  — ミニチュアの **Redis互換** インメモリkey-valueサーバー: 本物の
  `redis-cli` が動く程度のRESP2を話し (`PING`/`SET`/`GET`/`DEL`/`INCR`/...)、
  ハッシュテーブルの状態は接続をまたいで保持されます。

HTTPの *クライアント* 側については、ソケット上でプロトコルを手書きする
必要はありません — `rontolisp:fetch` を使ってください。
[HTTPリクエストガイド](http-fetch.md)を参照してください。
