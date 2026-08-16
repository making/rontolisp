# rontolisp:tls-listen-pem

`(rontolisp:tls-listen-pem cert-file key-file port &optional host)`

**PEM ファイル**から読み込んだ証明書と秘密鍵を提供する、待ち受け **TLS**
ソケットをバインドします — certbot / OpenSSL に馴染む
[`rontolisp:tls-listen`](rontolisp-tls-listen.md)(PKCS12 キーストアを取る版)の
対応版です。`cert-file` は PEM 証明書チェーン(リーフ証明書が先頭)、`key-file`
は対応する**暗号化されていない PKCS#8 秘密鍵**(`-----BEGIN PRIVATE KEY-----`、
RSA/EC/DSA/EdDSA)です。それ以外は `tls-listen` と同じで、リスナーハンドルは
[`rontolisp:tcp-accept`](rontolisp-tcp-accept.md)、
[`rontolisp:tcp-local-port`](rontolisp-tcp-local-port.md)、
[`close`](close.md) で扱え、受理した接続は最初の読み書きでハンドシェイクします。

ローカル開発用の自己署名証明書と鍵は OpenSSL で生成できます(`-nodes` により
暗号化されていない PKCS#8 鍵が書き出されます):

```bash
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
  -keyout key.pem -out cert.pem -days 365 -subj /CN=localhost \
  -addext subjectAltName=IP:127.0.0.1,DNS:localhost
```

以下の例は 1 接続だけの TLS エコーサーバーです。
`openssl s_client -connect 127.0.0.1:8443` で試せます(1 行入力すると返ってきます):

```console
(let* ((listener (rontolisp:tls-listen-pem "cert.pem" "key.pem" 8443))
       (sock (rontolisp:tcp-accept listener))    ; クライアントを待つ
       (line (read-line sock)))                  ; ここでハンドシェイクが行われる
  (write-line line sock)
  (close sock)
  (close listener))
```

## バックエンドのサポート

- **インタープリタ**: PEM ファイルを実行時に読み込むため、`cert-file` と
  `key-file` は実行時に計算した値でもかまいません。ファイルの不在、不正な証明書、
  暗号化された鍵やサポート外の鍵、使用中のポートはエラーを通知します。
- **JVM**: 証明書と鍵は**コンパイル時**にパースされ、生成されたクラスに
  キーストアが埋め込まれるため、`cert-file` と `key-file` は**文字列リテラル**で
  なければなりません(計算されたパスはコンパイルエラー)。相対パスはソース
  ファイルのディレクトリを基準に解決されます。コンパイル後のプログラムは実行時に
  PEM ファイルを必要としません。
- **WASM**: 非サポート — `tls-listen-pem` は Preview 1 と `--component` の
  どちらのモードでも**コンパイルエラー**です。`wasi:tls` の提案は
  **設計上クライアント専用**(どのドラフトにもサーバー側 TLS
  インターフェイスは存在しない)であり、TLS *サーバー*には WASM
  の経路がありません — `--component` で動くのはクライアント側
  ([`rontolisp:tls-connect`](rontolisp-tls-connect.md) /
  [`rontolisp:tls-upgrade`](rontolisp-tls-upgrade.md))だけです。
- **ブラウザプレイグラウンド**: 非サポート — ブラウザのサンドボックスには生の
  TCP ソケットがないため、`tls-listen-pem` はエラーを通知します。

## 制限事項

- 秘密鍵は**暗号化されていない PKCS#8**鍵(`-----BEGIN PRIVATE KEY-----`)で
  なければなりません。暗号化された鍵や、旧来の PKCS#1
  (`-----BEGIN RSA PRIVATE KEY-----`)や SEC1
  (`-----BEGIN EC PRIVATE KEY-----`)形式は読み込みません。
  `openssl pkcs8 -topk8 -nocrypt -in old.pem -out key.pem` で変換してください。
- クライアント証明書認証(相互 TLS)のオプションはありません。
- JVM バックエンドではパスはコンパイル時のリテラルです(バックエンドのサポート
  を参照)。JVM で実行時に選ぶキーストアが必要な場合は、PKCS12 ファイルを取る
  [`rontolisp:tls-listen`](rontolisp-tls-listen.md) を使ってください。
