# TCPソケット

`rontolisp` パッケージは素のTCPネットワーキングのための4つの関数と、両側の
暗号化版 (`tls-connect` と `tls-listen`) を提供します。
これらは **Common Lispの一部ではない** ため、`rontolisp:` 修飾子で参照します
([パッケージ](../reference/packages.md)を参照)。接続されたソケットはファイル
ストリームと同じハンドル空間の **双方向ストリームハンドル** なので、標準の
ストリーム関数がそのまま使えます: `read-line`、`write-line`、`write-string`、
`write-char`、`read-char`、`read-byte`、`write-byte`、`close`。バッファリングされるファイル出力と異なり、ソケットへの
書き込みは即時に送信され (`write-line` は行ごとにフラッシュ)、相手が接続を
閉じると `read-line` は `nil` を返します。ソケットが運ぶのはバイトです:
`write-string` は文字列のUTF-8バイトをワイヤーに書き出し、`read-char` はそこから
1文字を読み戻すため、同じハンドルに対して `read-byte` と `read-char` を混在
させられます。ストリーム終端での挙動はそれぞれのCommon Lispの既定に従います:
`read-char` と `read-byte` はeof引数を渡さない限り `end-of-file` をシグナルし
(`(read-char sock nil :eof)` なら `:eof` を返します)、`read-line` はファイルの
場合と同じく `nil` を返します。出力関数 (`print`、`princ`、`format`) はソケットを受け取りません。
`(format nil ...)` で文字列を作り、`write-line` または `write-string` で
送信してください。

| 関数 | 用途 |
|----------|---------|
| [`rontolisp:tcp-connect`](../reference/functions/rontolisp-tcp-connect.md) | クライアント接続を開く: `(rontolisp:tcp-connect host port)` |
| [`rontolisp:tcp-listen`](../reference/functions/rontolisp-tcp-listen.md) | リスニングソケットをバインドする: `(rontolisp:tcp-listen port &optional host)` |
| [`rontolisp:tcp-accept`](../reference/functions/rontolisp-tcp-accept.md) | クライアント接続を待つ: `(rontolisp:tcp-accept listener)` |
| [`rontolisp:tcp-local-port`](../reference/functions/rontolisp-tcp-local-port.md) | バインドされたポートを読み取る (ポート `0` でlistenした後に便利) |
| [`rontolisp:tls-connect`](../reference/functions/rontolisp-tls-connect.md) | **暗号化された**クライアント接続を開く: `(rontolisp:tls-connect host port)` |
| [`rontolisp:tls-upgrade`](../reference/functions/rontolisp-tls-upgrade.md) | **接続済み**のストリームハンドルをクライアントとして TLS でラップする: `(rontolisp:tls-upgrade stream host)` |
| [`rontolisp:tls-listen`](../reference/functions/rontolisp-tls-listen.md) | PKCS12 キーストアから**暗号化された**リスニングソケットをバインドする: `(rontolisp:tls-listen keystore password port &optional host)` |
| [`rontolisp:tls-listen-pem`](../reference/functions/rontolisp-tls-listen-pem.md) | PEM ファイルから**暗号化された**リスニングソケットをバインドする: `(rontolisp:tls-listen-pem cert-file key-file port &optional host)` |

> **バックエンドのサポート。** インタプリタとJVMコンパイル済みクラスはJDKの
> ソケットクラスを使い、ホスト名とIPリテラルの両方を受け付けます。WASM
> バックエンドは **componentモード専用** です (`--component`、
> `wasi:sockets@0.3.0` 経由): tcp関数はPreview 1 (コアモジュール) モードでは
> コンパイルエラーになり、ホストはIPv4リテラルでなければならず、component
> は通常のフラグに加えて `-W exceptions=y -S tcp=y -S inherit-network=y` を
> 付けて実行する必要があります (tcp componentは常にexception-handlingモードで
> コンパイルされます)。tcp関数と
> [`rontolisp:http-handler`](http-handler.md) の組み合わせは1つのcomponentに
> コンパイルでき、`wasmtime serve` で動きます — 上のフラグに `-S cli=y` を
> 追加してください (これがないとserveのリンカがインスタンス化時に
> `wasi:sockets@0.3.0` の `tcp-socket` リソースを missing と報告します)。
> wasmCloudの `wash dev` (2.5.2) もこのcomponentをホストでき、
> `wasi:sockets` 0.3 を提供します。1つ違いがあります: loopback宛の接続先は
> マシンの実際の127.0.0.1ではなく、workloadごとの仮想ネットワークを指します —
> loopbackアドレスへの接続は同じwasmCloud workload内のリスナー
> (そこにbindしたサービスcomponentなど) にのみ届き、loopback以外の
> アドレスへは実ネットワーク経由で接続します。**ブラウザプレイグラウンド** ではすべてのtcp関数がエラーを
> シグナルします (ブラウザのサンドボックスには素のTCPがありません) — 下の
> 実行可能な例はブラウザの外でのみ動作します。共通の制限 (TCPのみ、
> UDPなし) については
> [tcp-connect](../reference/functions/rontolisp-tcp-connect.md)
> のリファレンスページを参照してください。
> TLS関数
> ([`rontolisp:tls-connect`](../reference/functions/rontolisp-tls-connect.md)、
> [`rontolisp:tls-upgrade`](../reference/functions/rontolisp-tls-upgrade.md)、
> [`rontolisp:tls-listen`](../reference/functions/rontolisp-tls-listen.md)、
> [`rontolisp:tls-listen-pem`](../reference/functions/rontolisp-tls-listen-pem.md))
> はインタプリタ/JVM専用です (WASMバックエンドではコンパイルエラー)。

このガイドのプログラムは完全で自己完結しています: それぞれをファイルにコピーし、
任意のバックエンドで実行できます。使うのは `rontolisp:tcp-*`
プリミティブだけです。末尾の [usocket 互換シム](#the-usocket-compatible-shim) では、
既存の Common Lisp コードが期待するポータビリティ API を通して同じプログラムが
どう見えるかを示します。

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
以下を `echo-server.lisp` として保存してください。acceptしたハンドルは
`read-line` が `nil` を返すまで (クライアントが切断するまで) 1行ずつ読まれ、
各行はそのまま書き戻されます:

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
wasmtime run -W gc=y -W exceptions=y -S tcp=y -S inherit-network=y echo-server.wasm
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

## echoクライアント

対応するクライアントはサーバーに接続し、標準入力から読んだ各行を送信し、
stdinが尽きるまで各応答を表示します。`echo-client.lisp` として保存してください:

```console
(let ((sock (rontolisp:tcp-connect "127.0.0.1" 7777)))
  (if sock
      (do ((line (read-line) (read-line)))
          ((null line) (close sock))
        (write-line line sock)
        (write-line (read-line sock)))
      (write-line "cannot connect to 127.0.0.1:7777 (is echo-server.lisp running?)")))
```

先に `echo-server.lisp` を起動し (任意のバックエンド)、クライアントに入力を
パイプします — サーバーとクライアントはそれぞれ *別々の* バックエンドで
実行できます:

```bash
echo hello | rontolisp echo-client.lisp
```

## HTTPサーバー

ソケットハンドルは行ストリームであり、`read-line` は末尾の復帰を1つ取り除く
ため、HTTPのCRLF終端のリクエスト行とヘッダーは普通の行として読めます
(ヘッダーを終える空行は `""` として読めます)。レスポンスのヘッダー行は
`write-line` が改行を付ける前に `code-char 13` で復帰を取り戻します。これだけで
`curl` やブラウザに応答できます。以下を `http-hello.lisp` として保存してください —
リクエスト行と実行中のリクエストカウンタを表示する小さなHTMLページを、
リクエストごとに1接続で提供します:

```console
;; Appends the carriage return of an HTTP CRLF line ending (write-line then
;; appends the newline).
(defun crlf (s)
  (concatenate 'string s (format nil "~a" (code-char 13))))

;; Consumes the request headers up to the blank line that ends them.
(defun drain-headers (sock)
  (do ((line (read-line sock) (read-line sock)))
      ((or (null line) (string= line "")))))

(let ((listener (rontolisp:tcp-listen 8080)))
  (if listener
      (progn
        (write-line "http server listening on http://127.0.0.1:8080/")
        (do ((n 1 (+ n 1))) (nil)
          (let* ((sock (rontolisp:tcp-accept listener))
                 (request (read-line sock)))
            (if request
                (let ((body (format nil "<h1>hello from rontolisp</h1><p>request ~a: ~a</p>" n request)))
                  (drain-headers sock)
                  (write-line (crlf "HTTP/1.1 200 OK") sock)
                  (write-line (crlf "Content-Type: text/html") sock)
                  ;; + 1: write-line terminates the body with a newline
                  (write-line (crlf (format nil "Content-Length: ~a" (+ (length body) 1))) sock)
                  (write-line (crlf "Connection: close") sock)
                  (write-line (crlf "") sock)
                  (write-line body sock)
                  (write-line (format nil "served request ~a: ~a" n request))))
            (close sock))))
      (write-line "tcp-listen failed (is port 8080 already in use?)")))
```

任意のバックエンドで実行し、ブラウザで <http://127.0.0.1:8080/> を開くか
`curl http://127.0.0.1:8080/` でアクセスしてください。

> 実際のHTTP用途では、どちらの向きでもソケット上でプロトコルを手書きする
> 必要はありません。*クライアント* 側は `rontolisp:fetch`
> ([HTTPリクエストガイド](http-fetch.md)参照)、*サーバ* 側は
> `rontolisp:http-handler` がリクエストのパースとレスポンスの変換を引き受けます
> ([HTTP サーバガイド](http-handler.md)参照)。上の手書きサーバーはソケット
> プリミティブを示すためのもので、HTTPを提供する推奨手段ではありません。

## ミニチュアRedisサーバー

より大きな例: 本物の `redis-cli` が接続して動く程度のRESP2 (Redisの
シリアライゼーションプロトコル) を話すインメモリkey-valueサーバーです。
本物のRedisと同じく "インラインコマンド" (空白区切りの素の行) も受け付けるため、
`telnet 127.0.0.1 6379` や `nc 127.0.0.1 6379` でも動きます。どちらのフレーミングも
CRLF終端の行として届き、`read-line` が普通の行として読みます。ストアは文字列
キーのハッシュテーブルで、接続をまたいで保持されます。(大文字小文字を区別せず)
`PING`、`SET`、`GET`、`DEL`、`EXISTS`、`INCR`、`KEYS`、`DBSIZE`、`QUIT` を
サポートします。`kv-server.lisp` として保存してください:

```console
;; --- small string helpers ---------------------------------------------------

;; Appends the carriage return of a RESP CRLF line ending (write-line then
;; appends the newline).
(defun crlf (s)
  (concatenate 'string s (format nil "~a" (code-char 13))))

;; "SET key value" -> ("SET" "key" "value")
(defun split-words (s)
  (cond ((string= s "") nil)
        (t (let ((p (position #\space s)))
             (if p
                 (cons (subseq s 0 p) (split-words (subseq s (+ p 1))))
                 (list s))))))

;; ("hello" "world") -> "hello world"
(defun join-words (ws)
  (cond ((null ws) "")
        ((null (cdr ws)) (car ws))
        (t (concatenate 'string (car ws) " " (join-words (cdr ws))))))

;; t when s is a non-empty run of decimal digits (with an optional leading -).
(defun integer-string-p (s)
  (let* ((n (length s))
         (start (if (and (> n 0) (char= (char s 0) #\-)) 1 0)))
    (and (> n start)
         (do ((i start (+ i 1)))
             ((or (>= i n) (not (digit-char-p (char s i))))
              (>= i n))))))

;; --- RESP replies -----------------------------------------------------------

(defun reply-simple (s sock)
  (write-line (crlf (concatenate 'string "+" s)) sock))

(defun reply-error (s sock)
  (write-line (crlf (concatenate 'string "-ERR " s)) sock))

(defun reply-int (n sock)
  (write-line (crlf (format nil ":~a" n)) sock))

(defun reply-bulk (s sock)
  (if s
      (progn
        (write-line (crlf (format nil "$~a" (length s))) sock)
        (write-line (crlf s) sock))
      (write-line (crlf "$-1") sock)))

(defun reply-array-header (n sock)
  (write-line (crlf (format nil "*~a" n)) sock))

;; --- request framing --------------------------------------------------------

;; Reads one RESP bulk-string element: the "$<len>" header line, then the
;; payload line (the payload must not contain a newline).
(defun read-bulk (sock)
  (let ((header (read-line sock)))
    (if header (read-line sock) nil)))

(defun read-resp-array (count sock acc)
  (if (<= count 0)
      (reverse acc)
      (let ((arg (read-bulk sock)))
        (if arg
            (read-resp-array (- count 1) sock (cons arg acc))
            nil))))

;; Reads one command as a list of argument strings: a "*<n>" line starts a
;; RESP2 array (what redis-cli sends); anything else is an inline command
;; (what telnet/nc users type). nil at connection close.
(defun read-command (sock)
  (let ((line (read-line sock)))
    (cond ((null line) nil)
          ((string= line "") (read-command sock))
          ((char= (char line 0) #\*)
           (let ((count (subseq line 1)))
             (if (integer-string-p count)
                 (read-resp-array (parse-integer count) sock nil)
                 (list "!bad-frame"))))
          (t (split-words line)))))

;; --- commands ---------------------------------------------------------------

;; Handles one command; returns nil after QUIT (closing the session).
(defun handle-command (args store sock)
  (let ((cmd (string-upcase (car args)))
        (key (cadr args)))
    (cond ((string= cmd "PING")
           (if key (reply-bulk key sock) (reply-simple "PONG" sock))
           t)
          ((string= cmd "SET")
           (if (and key (cddr args))
               (progn
                 (setf (gethash key store) (join-words (cddr args)))
                 (reply-simple "OK" sock))
               (reply-error "wrong number of arguments for 'set' command" sock))
           t)
          ((string= cmd "GET")
           (if key
               (reply-bulk (gethash key store) sock)
               (reply-error "wrong number of arguments for 'get' command" sock))
           t)
          ((string= cmd "DEL")
           (let ((removed 0))
             (dolist (k (cdr args))
               (when (gethash k store)
                 (remhash k store)
                 (incf removed)))
             (reply-int removed sock))
           t)
          ((string= cmd "EXISTS")
           (reply-int (if (and key (gethash key store)) 1 0) sock)
           t)
          ((string= cmd "INCR")
           (let ((current (if key (or (gethash key store) "0") "0")))
             (cond ((null key)
                    (reply-error "wrong number of arguments for 'incr' command" sock))
                   ((integer-string-p current)
                    (let ((n (+ (parse-integer current) 1)))
                      (setf (gethash key store) (format nil "~a" n))
                      (reply-int n sock)))
                   (t (reply-error "value is not an integer or out of range" sock))))
           t)
          ((string= cmd "KEYS")
           (let ((pattern (or key "*"))
                 (keys nil))
             (maphash (lambda (k v)
                        (if (or (string= pattern "*") (string= pattern k))
                            (push k keys)))
                      store)
             (reply-array-header (length keys) sock)
             (dolist (k keys)
               (reply-bulk k sock)))
           t)
          ((string= cmd "DBSIZE")
           (reply-int (hash-table-count store) sock)
           t)
          ((string= cmd "COMMAND")
           ;; redis-cli asks COMMAND DOCS on connect; an empty array satisfies it.
           (reply-array-header 0 sock)
           t)
          ((string= cmd "QUIT")
           (reply-simple "OK" sock)
           nil)
          (t (reply-error (format nil "unknown command '~a'" (car args)) sock)
             t))))

;; --- server loop ------------------------------------------------------------

(let ((store (make-hash-table))
      (listener (rontolisp:tcp-listen 6379)))
  (if listener
      (progn
        (write-line "mini-redis listening on 127.0.0.1:6379 (try: redis-cli -p 6379 ping)")
        (do ((n 1 (+ n 1))) (nil)
          (let ((sock (rontolisp:tcp-accept listener)))
            (do ((args (read-command sock) (read-command sock)))
                ((or (null args) (not (handle-command args store sock)))
                 (close sock))))))
      (write-line "tcp-listen failed (is port 6379 already in use? a real redis, perhaps)")))
```

任意のバックエンドで実行し、本物の `redis-cli` で会話してください:

```bash
redis-cli -p 6379 set greeting hello
redis-cli -p 6379 get greeting
redis-cli -p 6379 incr counter
```

## TLS接続

[`rontolisp:tls-connect`](../reference/functions/rontolisp-tls-connect.md) は
`tcp-connect` の暗号化版です: 接続後に TLS ハンドシェイクを行い、同じ種類の
ストリームハンドルを返すため、`read-line`、`write-line`、`read-byte`、
`write-byte`、`close` がそのまま使えます。サーバー証明書は JDK デフォルトの
トラストストアで検証され、ホスト名も検証されます。自己署名証明書を受け入れる
には `javax.net.ssl.trustStore` システムプロパティで独自のトラストストアを
指定するか、`:insecure t` を渡して検証を完全にスキップします(開発用途のみ)。
詳細と手書き HTTPS の例はリファレンスページを参照してください:

```console
(let ((sock (rontolisp:tls-connect "example.com" 443)))
  ...  ; speak any TLS-wrapped protocol over the handle
  (close sock))
```

**すでに開いた接続の上で** TLS を開始するには — HTTP クライアントライブラリが
必要とする形です。クライアントはまず接続し(場合によってはプロキシへ
`CONNECT` を発行し)、その後で TLS を開始するからです —
[`rontolisp:tls-upgrade`](../reference/functions/rontolisp-tls-upgrade.md)
を使います: 既存のソケットハンドルと検証対象のサーバー名を受け取り、同じ接続の
上の新しいハンドルを返します。バンドルされた
[`cl+ssl` シムシステム](asdf-systems.md#built-in-shim-systems)はこの上に
実装されており、`usocket`+`cl+ssl` を使うクライアントライブラリの `https://`
経路を提供します。

*サーバー*側は
[`rontolisp:tls-listen`](../reference/functions/rontolisp-tls-listen.md) です:
PKCS12 キーストアファイルを受け取り、プレーンな
`rontolisp:tcp-accept` / `rontolisp:tcp-local-port` / `close` がそのまま使える
リスナーを返します。accept された各接続は最初の読み取りでハンドシェイクを
完了します。PKCS12 キーストアの代わりに PEM ファイル(certbot / OpenSSL の
出力)から直接提供するには、
[`rontolisp:tls-listen-pem`](../reference/functions/rontolisp-tls-listen-pem.md)
を使ってください。TLS 版はインタプリタ/JVM 専用です (WASM バックエンドでは
コンパイルエラー)。

以下の2つのサーバープログラムはいずれもサーバーの鍵と証明書を格納した PKCS12
キーストアを必要とします。localhost 用の自己署名キーストアを JDK の `keytool`
で生成してください (または OpenSSL から `openssl pkcs12 -export` でエクスポート):

```bash
keytool -genkeypair -alias rontolisp-tls -keyalg EC -dname CN=localhost \
  -validity 365 -ext SAN=ip:127.0.0.1,dns:localhost \
  -storetype PKCS12 -keystore tls-server.p12 \
  -storepass changeit -keypass changeit
```

### HTTPSサーバー

これは上の HTTP サーバーの TLS 版です: リスナーができてしまえば同一です。
`tls-listen` のリスナーは `tcp-accept` に同じ種類のストリームハンドルを
渡すからです。`tls-listen` は決して `nil` を返しません — キーストアの欠如、
誤ったパスワード、使用中のポートはいずれもエラーをシグナルします — ので
`nil` チェックはありません。`https-hello.lisp` として保存してください:

```console
;; Appends the carriage return of an HTTP CRLF line ending (write-line then
;; appends the newline).
(defun crlf (s)
  (concatenate 'string s (format nil "~a" (code-char 13))))

;; Consumes the request headers up to the blank line that ends them.
(defun drain-headers (sock)
  (do ((line (read-line sock) (read-line sock)))
      ((or (null line) (string= line "")))))

(let ((listener (rontolisp:tls-listen "tls-server.p12" "changeit" 8443)))
  (write-line "https server listening on https://127.0.0.1:8443/")
  (do ((n 1 (+ n 1))) (nil)
    (let* ((sock (rontolisp:tcp-accept listener))
           (request (read-line sock)))
      (if request
          (let ((body (format nil "<h1>hello from rontolisp over TLS</h1><p>request ~a: ~a</p>" n request)))
            (drain-headers sock)
            (write-line (crlf "HTTP/1.1 200 OK") sock)
            (write-line (crlf "Content-Type: text/html") sock)
            ;; + 1: write-line terminates the body with a newline
            (write-line (crlf (format nil "Content-Length: ~a" (+ (length body) 1))) sock)
            (write-line (crlf "Connection: close") sock)
            (write-line (crlf "") sock)
            (write-line body sock)
            (write-line (format nil "served request ~a: ~a" n request))))
      (close sock))))
```

インタプリタまたは JVM で実行し、次のようにアクセスします (証明書が自己署名
なので `-k` を付けます):

```bash
curl -k https://127.0.0.1:8443/
```

### TLS Redisサーバー

key-value サーバーでも同じです: `tcp-listen` を `tls-listen` に置き換えるだけで、
他はすべて同一です。これは RESP2 プロトコルをポート 6380 で TLS 提供します
(本物の Redis の `--tls-port` のように)。`kv-server-tls.lisp` として保存して
ください:

```console
;; --- small string helpers ---------------------------------------------------

;; Appends the carriage return of a RESP CRLF line ending (write-line then
;; appends the newline).
(defun crlf (s)
  (concatenate 'string s (format nil "~a" (code-char 13))))

;; "SET key value" -> ("SET" "key" "value")
(defun split-words (s)
  (cond ((string= s "") nil)
        (t (let ((p (position #\space s)))
             (if p
                 (cons (subseq s 0 p) (split-words (subseq s (+ p 1))))
                 (list s))))))

;; ("hello" "world") -> "hello world"
(defun join-words (ws)
  (cond ((null ws) "")
        ((null (cdr ws)) (car ws))
        (t (concatenate 'string (car ws) " " (join-words (cdr ws))))))

;; t when s is a non-empty run of decimal digits (with an optional leading -).
(defun integer-string-p (s)
  (let* ((n (length s))
         (start (if (and (> n 0) (char= (char s 0) #\-)) 1 0)))
    (and (> n start)
         (do ((i start (+ i 1)))
             ((or (>= i n) (not (digit-char-p (char s i))))
              (>= i n))))))

;; --- RESP replies -----------------------------------------------------------

(defun reply-simple (s sock)
  (write-line (crlf (concatenate 'string "+" s)) sock))

(defun reply-error (s sock)
  (write-line (crlf (concatenate 'string "-ERR " s)) sock))

(defun reply-int (n sock)
  (write-line (crlf (format nil ":~a" n)) sock))

(defun reply-bulk (s sock)
  (if s
      (progn
        (write-line (crlf (format nil "$~a" (length s))) sock)
        (write-line (crlf s) sock))
      (write-line (crlf "$-1") sock)))

(defun reply-array-header (n sock)
  (write-line (crlf (format nil "*~a" n)) sock))

;; --- request framing --------------------------------------------------------

;; Reads one RESP bulk-string element: the "$<len>" header line, then the
;; payload line (the payload must not contain a newline).
(defun read-bulk (sock)
  (let ((header (read-line sock)))
    (if header (read-line sock) nil)))

(defun read-resp-array (count sock acc)
  (if (<= count 0)
      (reverse acc)
      (let ((arg (read-bulk sock)))
        (if arg
            (read-resp-array (- count 1) sock (cons arg acc))
            nil))))

;; Reads one command as a list of argument strings: a "*<n>" line starts a
;; RESP2 array (what redis-cli sends); anything else is an inline command
;; (what telnet/nc users type). nil at connection close.
(defun read-command (sock)
  (let ((line (read-line sock)))
    (cond ((null line) nil)
          ((string= line "") (read-command sock))
          ((char= (char line 0) #\*)
           (let ((count (subseq line 1)))
             (if (integer-string-p count)
                 (read-resp-array (parse-integer count) sock nil)
                 (list "!bad-frame"))))
          (t (split-words line)))))

;; --- commands ---------------------------------------------------------------

;; Handles one command; returns nil after QUIT (closing the session).
(defun handle-command (args store sock)
  (let ((cmd (string-upcase (car args)))
        (key (cadr args)))
    (cond ((string= cmd "PING")
           (if key (reply-bulk key sock) (reply-simple "PONG" sock))
           t)
          ((string= cmd "SET")
           (if (and key (cddr args))
               (progn
                 (setf (gethash key store) (join-words (cddr args)))
                 (reply-simple "OK" sock))
               (reply-error "wrong number of arguments for 'set' command" sock))
           t)
          ((string= cmd "GET")
           (if key
               (reply-bulk (gethash key store) sock)
               (reply-error "wrong number of arguments for 'get' command" sock))
           t)
          ((string= cmd "DEL")
           (let ((removed 0))
             (dolist (k (cdr args))
               (when (gethash k store)
                 (remhash k store)
                 (incf removed)))
             (reply-int removed sock))
           t)
          ((string= cmd "EXISTS")
           (reply-int (if (and key (gethash key store)) 1 0) sock)
           t)
          ((string= cmd "INCR")
           (let ((current (if key (or (gethash key store) "0") "0")))
             (cond ((null key)
                    (reply-error "wrong number of arguments for 'incr' command" sock))
                   ((integer-string-p current)
                    (let ((n (+ (parse-integer current) 1)))
                      (setf (gethash key store) (format nil "~a" n))
                      (reply-int n sock)))
                   (t (reply-error "value is not an integer or out of range" sock))))
           t)
          ((string= cmd "KEYS")
           (let ((pattern (or key "*"))
                 (keys nil))
             (maphash (lambda (k v)
                        (if (or (string= pattern "*") (string= pattern k))
                            (push k keys)))
                      store)
             (reply-array-header (length keys) sock)
             (dolist (k keys)
               (reply-bulk k sock)))
           t)
          ((string= cmd "DBSIZE")
           (reply-int (hash-table-count store) sock)
           t)
          ((string= cmd "COMMAND")
           ;; redis-cli asks COMMAND DOCS on connect; an empty array satisfies it.
           (reply-array-header 0 sock)
           t)
          ((string= cmd "QUIT")
           (reply-simple "OK" sock)
           nil)
          (t (reply-error (format nil "unknown command '~a'" (car args)) sock)
             t))))

;; --- server loop ------------------------------------------------------------

(let ((store (make-hash-table))
      (listener (rontolisp:tls-listen "tls-server.p12" "changeit" 6380)))
  (write-line "mini-redis (TLS) listening on 127.0.0.1:6380 (try: redis-cli --tls --insecure -p 6380 ping)")
  (do ((n 1 (+ n 1))) (nil)
    (let ((sock (rontolisp:tcp-accept listener)))
      (do ((args (read-command sock) (read-command sock)))
          ((or (null args) (not (handle-command args store sock)))
           (close sock))))))
```

インタプリタまたは JVM で実行し、TLS 経由で会話します (証明書が自己署名なので
`--insecure` を付けます):

```bash
redis-cli --tls --insecure -p 6380 set greeting hello
redis-cli --tls --insecure -p 6380 get greeting
```

## usocket 互換シム

既存の Common Lisp のネットワークコードは、処理系固有のソケット API では
なく [usocket](https://github.com/usocket/usocket) ポータビリティ
ライブラリに対して書かれていることがほとんどです。rontolisp は
`rontolisp:tcp-*` 組み込みの上でそのコア API を再現する組み込みの
`usocket` パッケージを備えているため、そうしたコードがより少ない変更で
動きます -- Postmodern の cl-postgres ソケット層
(`:element-type '(unsigned-byte 8)` 付きの `socket-connect` +
`socket-stream`)はそのまま動きます:

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

rontolisp のソケットはストリームハンドルそのものなので、対応は直接的です:
`usocket:socket-stream` は恒等関数、`usocket:socket-close` は `close`、
`usocket:socket-listen` はホストが先の引数順を `rontolisp:tcp-listen` に
変換します。`usocket:*wildcard-host*`(`"0.0.0.0"`)と
`usocket:*auto-port*`(`0`)は usocket と同じように動き、`get-local-*` /
`get-peer-*` アクセサでポートとアドレスを読み戻せます。`with-*` 便利マクロ
([`with-client-socket` / `with-connected-socket` / `with-server-socket` /
`with-socket-listener`](../reference/macros/usocket-with-macros.md))は
本体の前後でソケットを束縛して閉じます。パッケージは最初の使用時に
ロードされ、組み込み ASDF システム `"usocket"` でもあります:
`(asdf:load-system "usocket")`、`(ql:quickload :usocket)`、サードパーティ
`.asd` の `:depends-on ("usocket")` はいずれもネットワークに触れずに
解決されます。

このガイドで先に示したサーバーをこのシムで書き直すと、acceptループを
`with-server-socket`(あらゆる脱出時に各接続を閉じます)で包み、listen の失敗を
型付きの `usocket:socket-error` として受けます:

```console
(handler-case
    (let ((listener (usocket:socket-listen "127.0.0.1" 7777 :reuse-address t)))
      (write-line "echo server listening on 127.0.0.1:7777")
      (do ((n 1 (+ n 1))) (nil)
        (usocket:with-server-socket (sock (usocket:socket-accept listener))
          (let ((stream (usocket:socket-stream sock)))
            (write-line (format nil "client ~a connected" n))
            (do ((line (read-line stream) (read-line stream)))
                ((null line) (write-line "client disconnected"))
              (write-line line stream))))))
  (usocket:socket-error (e)
    (declare (ignore e))
    (write-line "socket-listen failed (is port 7777 already in use?)")))
```

シムの制限(意図的なものです -- rontolisp のソケットモデルは lite です):

- **TCP のみ。** `:protocol :datagram`(UDP)はエラーを通知し、
  `socket-send` / `socket-receive` / `socket-shutdown` は存在しません。
- **インタープリタと JVM では型付きコンディション。**
  `socket-connect`/`socket-listen`/`socket-accept` の失敗は型付きの
  `usocket:socket-error` を通知します(メッセージは保持)。そのため
  `(handler-case (usocket:socket-connect ...) (usocket:socket-error (e) ...))`
  が動作します。サブタイプ(`connection-refused-error` など)も定義されますが、
  再通知は常に `socket-error` を使います(そちらを捕捉してください)。WASM
  コンポーネントバックエンドでは connect/accept の失敗はシグナルせず `nil`
  ハンドルを返すため、そこでは `handler-case` パターンには捕捉対象がありません
  (代わりにハンドルの `nil` を判定してください)。`wait-for-input` 的な
  コンディション処理は存在しません。
- **`wait-for-input` と `socket-server` は存在しません**(読み込みは
  ブロックします。accept ループは自分で書いてください)。
- **`with-*` マクロはインタープリタと JVM ではあらゆる脱出時にソケットを
  閉じます**([`unwind-protect`](../reference/special-forms/unwind-protect.md)
  に展開されます)。これは WASM コンポーネントバックエンドでも成り立ちます
  (tcp コンポーネントはもともと `-W exceptions=y` 付きで実行されます)。互換性の
  ためのキーワード引数(`:element-type`、`:timeout`、`:nodelay`、
  `:reuse-address` など)は受理して無視します。
- **バックエンド**: インタープリタと JVM はフル対応。WASM は tcp 組み込みと
  同じくコンポーネント専用で、アドレス系・peer 系アクセサはそこでも実際の
  アドレスとポートを返します(失敗時はエラーを通知せず `nil` を返します)。

## 関連情報

[`examples/net/` ディレクトリ](https://github.com/making/rontolisp/tree/develop/examples/net)
にはこれらのプログラムがすぐ実行できるファイル (usocket シムで記述) として
同梱されており、それぞれのヘッダーコメントにバックエンドごとの実行手順が
書かれています。HTTP については、どちらの向きでもソケット上でプロトコルを
手書きする必要はありません。*クライアント* 側は `rontolisp:fetch`
([HTTPリクエストガイド](http-fetch.md)参照)、*サーバ* 側は
`rontolisp:http-handler` がリクエストのパースとレスポンスの変換を引き受けます
([HTTP サーバガイド](http-handler.md)参照)。
