# rontolisp:tls-upgrade

`(rontolisp:tls-upgrade stream host)`
`(rontolisp:tls-upgrade stream host :insecure value)`

**接続済み**の TCP ストリームハンドルをクライアントとして **TLS** でラップします:
既存の接続の上でハンドシェイクを行い、暗号化ストリームを担う**新しい**ストリーム
ハンドルを返します。[`rontolisp:tls-connect`](rontolisp-tls-connect.md) が新規に
接続を開くのに対し、`tls-upgrade` は先に
[`rontolisp:tcp-connect`](rontolisp-tcp-connect.md)(または
`usocket:socket-connect`)が返したハンドルを受け取ります — HTTP クライアント
ライブラリが必要とする形です。クライアントはまず接続し(場合によってはプロキシへ
`CONNECT` を発行し)、その後で TLS を開始するからです。返されたハンドルには標準の
ストリーム関数([`read-line`](read-line.md)、[`write-line`](write-line.md)、
[`read-byte`](read-byte.md)、[`write-byte`](write-byte.md)、
[`close`](close.md))がそのまま使え、閉じると下層の接続も閉じます。

サーバー証明書は JDK デフォルトのトラストストアで検証され、`host` に対して
ホスト名も検証されます(HTTPS 方式のエンドポイント識別。`host` は SNI サーバー名
としても送信されます)。自己署名証明書を信頼するには、標準の
`javax.net.ssl.trustStore` / `javax.net.ssl.trustStorePassword` システム
プロパティで独自のトラストストアを指定してください。これらは呼び出しのたびに
再読み込みされます。`:insecure` に `nil` 以外の `value` を渡すと両方の検証を
無効化します — `tls-connect` のオプションと同じく開発用途限定です。

これはバンドルされた
[`cl+ssl` シムシステム](../../guides/asdf-systems.md#built-in-shim-systems)の
基盤プリミティブです: `cl+ssl:make-ssl-client-stream` — あらゆる CL の HTTP
クライアント(dexador、drakma など)が `https://` URL のために呼ぶ関数 — は、
渡されたストリームを `tls-upgrade` でアップグレードします。

以下の例はプレーン接続をアップグレードして HTTPS を手書きで話します(実際の
HTTPS リクエストには [`rontolisp:fetch`](rontolisp-fetch.md) を使ってください):

```console
(let* ((sock (rontolisp:tcp-connect "example.com" 443))
       (tls (rontolisp:tls-upgrade sock "example.com"))
       (cr (princ-to-string (code-char 13))))
  (write-line (concatenate 'string "HEAD / HTTP/1.1" cr) tls)
  (write-line (concatenate 'string "Host: example.com" cr) tls)
  (write-line (concatenate 'string "Connection: close" cr) tls)
  (write-line cr tls)
  (print (read-line tls))   ; "HTTP/1.1 200 OK"
  (close tls))
```

## バックエンドのサポート

- **インタープリタ**と **JVM**: JDK の TLS スタック
  (`SSLSocketFactory.createSocket(socket, host, port, true)`)を使います。
  ハンドシェイクの失敗(信頼されていない証明書、ホスト名の不一致、TLS を話さない
  相手)はエラーを通知します。
- **WASM**: まだサポートされません — TLS ファミリーの他の関数と同じく、
  `tls-upgrade` は Preview 1 と `--component` モードの両方で**コンパイルエラー**
  です。wasmtime の実験的な `wasi:tls@0.3.0-draft` インターフェースでホスト
  できる可能性はありますが、不安定(semver 保証なし)なため、安定するまでは
  インタープリタか JVM バックエンドを使ってください。
- **ブラウザプレイグラウンド**: サポートされません — ブラウザのサンドボックスには
  生の TCP ソケットがないため、`tls-upgrade` はエラーを通知します。

## 制限事項

- `stream` は**接続済みのソケットハンドル**(`tcp-connect` または `tcp-accept`
  由来)でなければなりません。リスナーやファイルストリームのハンドルはエラーを
  通知します。元のハンドルは下層の生の接続を指したままなので、アップグレード後は
  新しいハンドルだけで読み書きしてください。
- クライアント証明書はサポートされません(クライアント側の身元を提示する手段が
  ありません)。`cl+ssl` シムが `:key`/`:certificate`/`:password` オプションで
  エラーを通知するのはこのためです — 黙って未認証のまま接続するよりも明確な
  メッセージのほうが良いからです。
- `:insecure` は `tls-connect` と同じく全か無かのオプトアウトです。
