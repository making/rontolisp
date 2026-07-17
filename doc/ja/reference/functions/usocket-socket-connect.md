# usocket:socket-connect

`(usocket:socket-connect host port &key protocol element-type timeout deadline nodelay local-host local-port)`

`host` と `port` へブロッキングの TCP 接続を開き、ソケットを返します --
[`rontolisp:tcp-connect`](rontolisp-tcp-connect.md) の上に載った
[usocket](https://github.com/usocket/usocket) 互換のエントリポイントです。
このシムではソケットはストリームハンドルそのものなので、
`usocket:socket-stream` は恒等関数で、ストリーム組み込み(`read-line`、
`write-line`、`write-string`、`read-byte`、`write-byte`、`close`)がそのまま使えます。

対応は TCP のみ: `:protocol :datagram`(UDP)はエラーを通知します。他の
キーワード引数は互換性のために受理して無視します -- 特に `:element-type` は、
rontolisp のソケットハンドルが常に双方向で行系・バイト系両方の組み込みに
対応するため(バイナリ/文字ストリームを構築時に選ぶ必要がありません)。

cl-postgres(Postmodern)の接続コードの形がそのまま動きます:

```console
(usocket:socket-stream
 (usocket:socket-connect "localhost" 5432
                         :element-type '(unsigned-byte 8)))
```

ループバックでのエコー往復(シングルスレッドの段取り: accept の前に
connect、相手が読む前に write):

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

`usocket` パッケージは最初の使用時に自動でロードされ(インタープリタ)、
コンパイルされるプログラムには splice されます(JVM / WASM コンポーネント)。
組み込み ASDF システム `"usocket"` としても登録されているため、
`(asdf:load-system "usocket")`、`(ql:quickload :usocket)`、サードパーティ
`.asd` の `:depends-on ("usocket")` はいずれもネットワークに触れずに解決
されます。rontolisp にはコンディションシステムがないため、
`usocket:socket-error` などのコンディション型はデータシンボルとしてのみ
存在します -- 接続失敗はエラーを通知し(インタープリタ / JVM)、または
`nil` を返します(WASM コンポーネント)。usocket コンディションに対する
`handler-case` は非対応です。

## バックエンドごとの対応

- **インタープリタ**と **JVM**: フル対応(`rontolisp:tcp-connect` に委譲)。
- **WASM**: コンポーネントモード(`--component`)のみ、IPv4 リテラルのみ。
  接続失敗は `nil` を返します。Preview 1 はコンパイルエラーです。
- **ブラウザプレイグラウンド**: 非対応。
