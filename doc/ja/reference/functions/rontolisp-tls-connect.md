# rontolisp:tls-connect

`(rontolisp:tls-connect host port)`

`host`/`port` へブロッキング TCP 接続を開き、**TLS ハンドシェイク**を行って
**双方向ストリームハンドル**を返します —
[`rontolisp:tcp-connect`](rontolisp-tcp-connect.md) の暗号化版です。ハンドルは
ファイルストリームと同じハンドル空間にあるため、標準のストリーム関数がそのまま
使えます: [`read-line`](read-line.md)、[`write-line`](write-line.md)、
[`read-byte`](read-byte.md)、[`write-byte`](write-byte.md)、
[`close`](close.md)。プレーンなソケットと同様、書き込みは即座に送信され、相手が
接続を閉じると `read-line` は `nil` を返します。

サーバー証明書は JDK デフォルトのトラストストアで検証され、ホスト名も検証されます
(HTTPS 方式のエンドポイント識別)。そのため、信頼されていない証明書やホスト名の
一致しない証明書を持つサーバーへの接続はエラーを通知します。自己署名証明書を
信頼するには、標準の `javax.net.ssl.trustStore` /
`javax.net.ssl.trustStorePassword` システムプロパティで独自のトラストストアを
指定してください。これらは呼び出しのたびに再読み込みされます。

以下の例は TLS 上で HTTP/1.1 を手書きで話します(リクエスト行は CRLF で終わる
必要があるため、キャリッジリターンを明示的に付加しています。レスポンス側の CR は
`read-line` が取り除きます)。実際の HTTPS リクエストには
[`rontolisp:fetch`](rontolisp-fetch.md) を使ってください — `tls-connect` は
任意の TLS でラップされたプロトコルのためのものです:

```console
(let ((sock (rontolisp:tls-connect "example.com" 443))
      (cr (princ-to-string (code-char 13))))
  (write-line (concatenate 'string "GET / HTTP/1.1" cr) sock)
  (write-line (concatenate 'string "Host: example.com" cr) sock)
  (write-line (concatenate 'string "Connection: close" cr) sock)
  (write-line cr sock)
  (print (read-line sock))   ; "HTTP/1.1 200 OK"
  (close sock))
```

## バックエンドのサポート

- **インタープリタ**と **JVM**: JDK の TLS スタック(`SSLSocket`)を使います。
  `host` にはホスト名または IP リテラルを指定できます。接続やハンドシェイクの
  失敗(ポート拒否、信頼されていない証明書、ホスト名の不一致)はエラーを通知
  します。
- **WASM**: 非サポート — wasmtime は WASI 0.3 コンポーネント向けの TLS を
  ホストしていない(`wasi:tls` はまだ 0.2 のドラフト)ため、`tls-connect` は
  Preview 1 と `--component` のどちらのモードでも**コンパイルエラー**です。
- **ブラウザプレイグラウンド**: 非サポート — ブラウザのサンドボックスには生の
  TCP ソケットがないため、`tls-connect` はエラーを通知します。

## 制限事項

- 証明書検証を Lisp 側から無効化することはできません。追加の証明書を信頼するには
  トラストストアのシステムプロパティを使ってください。TLS の*サーバー*側は
  [`rontolisp:tls-listen`](rontolisp-tls-listen.md) を参照してください。
- `read`(S 式リーダー)はソケットハンドルでは動きません。行またはバイトで
  読み取り、明示的にパースしてください(例:
  [`read-from-string`](read-from-string.md))。
