# rontolisp:tls-listen

`(rontolisp:tls-listen keystore password port &optional host)`

リッスンする **TLS** ソケットをバインドします —
[`rontolisp:tcp-listen`](rontolisp-tcp-listen.md) の暗号化版です。`keystore`
はサーバーの秘密鍵と証明書を保持する **PKCS12 キーストアファイル**のパス、
`password` はそれを解錠するパスワードです。返されるリスナーハンドルには
プレーンな TCP 関数がそのまま使えます:
[`rontolisp:tcp-accept`](rontolisp-tcp-accept.md) で接続を受け付け、
[`rontolisp:tcp-local-port`](rontolisp-tcp-local-port.md) でバインドされた
ポートを読み取り(ポート `0` で listen した後に便利)、[`close`](close.md)
で停止します。accept された接続は通常の双方向ストリームハンドルで、TLS
ハンドシェイクは最初の読み書きで完了します。

ローカル開発用の自己署名キーストアは JDK の `keytool` で生成できます
(openssl からは `openssl pkcs12 -export` でエクスポートします):

```bash
keytool -genkeypair -alias my-server -keyalg EC -dname CN=localhost \
  -validity 365 -ext SAN=ip:127.0.0.1,dns:localhost \
  -storetype PKCS12 -keystore tls-server.p12 \
  -storepass changeit -keypass changeit
```

以下の例は 1 接続だけの TLS エコーサーバーです。
`openssl s_client -connect 127.0.0.1:8443` でテストできます(1 行入力すると
返ってきます):

```console
(let* ((listener (rontolisp:tls-listen "tls-server.p12" "changeit" 8443))
       (sock (rontolisp:tcp-accept listener))    ; blocks for a client
       (line (read-line sock)))                  ; the handshake happens here
  (write-line line sock)
  (close sock)
  (close listener))
```

完全なサーバーは `examples/` ディレクトリにあります:
[`https-hello.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/https-hello.lisp)
(`curl -k` が理解する HTTPS サーバー)と
[`kv-server-tls.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/kv-server-tls.lisp)
(本物の `redis-cli --tls` が話せる、TLS で提供されるミニ Redis)です。

## バックエンドのサポート

- **インタープリタ**と **JVM**: JDK の TLS スタックを使います。WASM
  バックエンドの `tcp-listen` と異なり、失敗が `nil` になることはありません:
  キーストアの欠落、誤ったパスワード、使用中のポートは、どちらのバックエンド
  でもエラーを通知します。
- **WASM**: 非サポート — `tls-listen` は Preview 1 と `--component` の
  どちらのモードでも**コンパイルエラー**です。`wasi:tls` の提案は
  **クライアント専用**(WASM コンポーネント向けのサーバー側 TLS
  インターフェイスは存在しない)であり、TLS *サーバー*には WASM
  の経路がありません。
- **ブラウザプレイグラウンド**: 非サポート — ブラウザのサンドボックスには生の
  TCP ソケットがないため、`tls-listen` はエラーを通知します。

## 制限事項

- キーストアは PKCS12 でなければなりません(`keytool` のデフォルト)。証明書と
  鍵を **PEM ファイル**(certbot / OpenSSL の出力)から直接提供するには、
  代わりに [`rontolisp:tls-listen-pem`](rontolisp-tls-listen-pem.md) を
  使ってください。
- クライアント証明書認証(相互 TLS)のオプションはありません。
- accept された接続のハンドシェイクは遅延実行です: 証明書検証に失敗する
  クライアントは、`rontolisp:tcp-accept` の時点ではなく、その接続に対する
  サーバー側の最初の読み書きでエラーとして現れます。
