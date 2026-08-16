# rontolisp:tls-connect

`(rontolisp:tls-connect host port)`
`(rontolisp:tls-connect host port :insecure value)`

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

`:insecure` に `nil` 以外の `value` を渡すと、両方の検証を**無効化**します —
証明書チェーンを無条件に受け入れ、ホスト名も検証しません。これは自己署名サーバー
に対する開発用途を想定したものです。中間者攻撃に対する保護がすべて失われるため、
実運用のエンドポイントには決して使わないでください。`:insecure nil` はオプションを
省略した場合(検証あり)と同じです。

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
- **WASM `--component`**(WASI 0.3): サポートされます。wasmtime の
  `wasi:tls@0.3.0-draft` インターフェイス上で動き、通常のソケット実行フラグ
  (`-W exceptions=y -S tcp=y -S inherit-network=y`)に `-S tls=y`
  を追加します。そこでの `tcp-connect` と同じく `host` は **IPv4 リテラル**
  (または `localhost`)でなければならず、さらに証明書の検証名を兼ねるため、
  実在のホストには「アドレスへの `tcp-connect` + DNS 名での
  [`rontolisp:tls-upgrade`](rontolisp-tls-upgrade.md)」を使ってください。
  失敗は WASM のエラー規約に従い、エラー通知ではなく `nil` を返します。
  証明書はホストに組み込まれたトラストアンカー(wasmtime は Mozilla
  ルートストアを同梱)で検証され、トラストストアのシステムプロパティと
  `:insecure` はそこでは効きません — 非 `nil` の `:insecure` 値は黙って
  検証する代わりに**エラーを通知**します。このインターフェイスは明示的に
  実験段階のドラフトなので、wasmtime の更新に rontolisp 側の追随が
  必要になることがあります。
- **WASM Preview 1**: 非サポート — **コンパイルエラー**です(Preview 1 には
  `wasi:tls` のホスト API が存在しません)。
- **ブラウザプレイグラウンド**: 非サポート — ブラウザのサンドボックスには生の
  TCP ソケットがないため、`tls-connect` はエラーを通知します。

## 制限事項

- `:insecure` はすべてを無効化するオプトアウトです(証明書ごとのピン留めは
  できません)。検証を有効にしたまま特定の追加証明書を信頼するには、代わりに
  トラストストアのシステムプロパティを使ってください。TLS の*サーバー*側は
  [`rontolisp:tls-listen`](rontolisp-tls-listen.md) を参照してください。
- `read`(S 式リーダー)はソケットハンドルでは動きません。行またはバイトで
  読み取り、明示的にパースしてください(例:
  [`read-from-string`](read-from-string.md))。
