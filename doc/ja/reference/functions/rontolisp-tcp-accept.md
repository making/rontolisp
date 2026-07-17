# rontolisp:tcp-accept

`(rontolisp:tcp-accept listener)`

クライアントが接続してくるまでブロックし
([`rontolisp:tcp-listen`](rontolisp-tcp-listen.md) のリスナーハンドルに対して)、
受け付けた接続の**双方向ストリームハンドル**を返します —
[`rontolisp:tcp-connect`](rontolisp-tcp-connect.md) が返すものと同じ種類の
ハンドルで、`read-line`、`write-line`、`write-string`、`read-byte`、
`write-byte`、`close` が使えます。

以下の例は自己完結しています: クライアントが accept より*前に*接続するため、
接続は listen バックログで待機し、シングルスレッドのプログラムでも長時間
ブロックしません:

```lisp
(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
       (port (rontolisp:tcp-local-port listener))
       (client (rontolisp:tcp-connect "127.0.0.1" port))
       (server (rontolisp:tcp-accept listener)))
  (write-byte 65 client)
  (let ((b (read-byte server)))
    (close server)
    (close client)
    (close listener)
    b))   ; => 65
```

## バックエンドごとの対応

- **インタープリタ**と **JVM**: `java.net.ServerSocket.accept()` を使用します。
  閉じたリスナーでの accept はエラーを通知します。
- **WASM**: コンポーネント専用。accept は `wasi:sockets@0.3.0` の accept
  ストリームから `tcp-socket` ハンドルを 1 つ読み出す協調ブロッキング読み取り
  です。async 本体では、保留中の accept はそのタスクだけをサスペンドするため、
  他のタスク(`rontolisp:wait-for` タイマーや別のリクエスト)は動き続けます。
  accept に失敗すると `nil` を返します。Preview 1(コアモジュール)
  モードではコンパイルエラーです。
- **ブラウザプレイグラウンド**: 非対応。

## 制限事項

- クライアントが接続するまで無期限にブロックします。タイムアウト引数は
  ありません。
- 1 回の呼び出しで処理できる接続は 1 つです。次のクライアントには再度 accept
  してください([`rontolisp:tcp-listen`](rontolisp-tcp-listen.md) のサーバー
  ループを参照)。
