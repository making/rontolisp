# rontolisp:tcp-listen

`(rontolisp:tcp-listen port &optional host)`

`port` で listen する TCP ソケットをバインドし、
[`rontolisp:tcp-accept`](rontolisp-tcp-accept.md) と [`close`](close.md) で
使う**リスナーハンドル**を返します。ポート `0` を指定すると空いている
エフェメラルポートが選ばれます — 実際のポートは
[`rontolisp:tcp-local-port`](rontolisp-tcp-local-port.md) で取得できます。
`host` を省略するとすべてのインターフェースにバインドし、`"127.0.0.1"` のような
アドレス文字列を渡すと特定のインターフェースだけにバインドします。

```lisp
(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
       (port (rontolisp:tcp-local-port listener)))
  (close listener)
  (> port 0))   ; => t
```

サーバーはループで接続を受け付けます。accept されたハンドルは双方向ストリーム
です([`rontolisp:tcp-accept`](rontolisp-tcp-accept.md) を参照):

```console
(let ((listener (rontolisp:tcp-listen 7777)))
  (do ((n 1 (+ n 1))) (nil)
    (let ((sock (rontolisp:tcp-accept listener)))
      (do ((line (read-line sock) (read-line sock)))
          ((null line) (close sock))
        (write-line line sock)))))   ; echo every client forever
```

## バックエンドごとの対応

- **インタープリタ**と **JVM**: JDK の `java.net.ServerSocket` を使用します
  (インタープリタでは `SO_REUSEADDR` 付き)。バインド失敗(ポート使用中など)は
  エラーを通知します。
- **WASM**: コンポーネント専用で、`wasi:sockets@0.3.0` 上で動作します。`host`
  は IPv4 リテラルである必要があります。`--component` でコンパイルし、非同期
  フラグに加えて `-S tcp=y -S inherit-network=y` を付けて実行します。バインド
  失敗は `nil` を返します。Preview 1(コアモジュール)モードでは
  コンパイルエラーです。
- **ブラウザプレイグラウンド**: 非対応(ブラウザのサンドボックスに生の TCP は
  ありません)。

## 制限事項

- リスナーハンドルが対応するのは `rontolisp:tcp-accept`、
  `rontolisp:tcp-local-port`、`close` のみで、それ自体はバイトストリームでは
  ありません。
- 共通のソケット制限(TCP のみ、WASM では IPv4 リテラルのみ)は
  [`rontolisp:tcp-connect`](rontolisp-tcp-connect.md) を参照してください。
  このリスナーが扱うのは平文の TCP です。TLS リスナーには
  [`rontolisp:tls-listen`](rontolisp-tls-listen.md) を使ってください
  (インタープリタ/JVM のみ)。
