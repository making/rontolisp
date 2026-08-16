# rontolisp:tcp-set-timeout

`(rontolisp:tcp-set-timeout handle milliseconds)`

接続済みソケットハンドルの読み取りデッドラインを設定します: 以降のこのハンドル
に対するブロッキング読み取り(`read-line`、`read-char`、`read-byte` など)は、
データが `milliseconds` ミリ秒間届かないと、永久に待つ代わりにエラーを通知する
ようになります。`milliseconds` は非負整数
([`rontolisp:wait-for`](rontolisp-wait-for.md) と同じ規約)で、`nil` は
デッドラインを解除します。戻り値は `milliseconds` 引数です。リスナーハンドルは
受け付けません(これは読み取りのデッドラインです)。

```lisp
(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
       (port (rontolisp:tcp-local-port listener))
       (sock (rontolisp:tcp-connect "127.0.0.1" port)))
  (rontolisp:tcp-set-timeout sock 200)
  (prog1 (handler-case (progn (read-line sock) :read)
           (error (e) :timed-out))   ; nothing is ever written -> the deadline fires
    (close sock)
    (close listener)))   ; => :TIMED-OUT
```

タイムアウトのエラーは、タイムアウトした読み取りをメッセージに含む通常の
捕捉可能な `error` で、専用のコンディションクラスではありません。デッドライン
は生のソケット側にあるため、あとから
[`rontolisp:tls-upgrade`](rontolisp-tls-upgrade.md) でアップグレードした接続にも
効き続けます。これは usocket シムの
`(setf (usocket:socket-option sock :receive-timeout) seconds)` を支える
プリミティブです
([TCP ソケットガイド](../../guides/tcp-sockets.md#the-usocket-compatible-shim)
を参照)。

## バックエンドのサポート

- **インタプリタ**と **JVM**: 実装済み。`Socket.setSoTimeout` を使用します。
- **WASM component**: 呼び出し時にエラーを通知します — `wasi:sockets@0.3.0`
  には受信タイムアウトのノブがなく、黙って発火しないタイムアウトはクライアント
  がまさに避けるために設定するものだからです。このバックエンドでは捕捉するか、
  読み取りタイムアウトを設定しないでください。Preview 1(コアモジュール)
  モードでは他の tcp 組み込みと同様、呼び出し時エラーです。
- **ブラウザプレイグラウンド**: 非対応。
