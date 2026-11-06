# rontolisp:tcp-connect

`(rontolisp:tcp-connect host port)`

`host`/`port` へブロッキングの TCP 接続を開き、**双方向のストリームハンドル**を
返します。このハンドルはファイルストリームと同じハンドル空間に属するため、標準の
ストリーム関数がそのまま使えます: [`read-line`](read-line.md)、
[`write-line`](write-line.md)、[`write-string`](write-string.md)、
[`write-char`](write-char.md)、[`read-char`](read-char.md)、
[`read-byte`](read-byte.md)、
[`write-byte`](write-byte.md)、[`close`](close.md)。バッファリングされる
ファイル出力と異なり、ソケットへの書き込みは即時に送信されます(`write-line` は
行ごとにフラッシュ)。相手側が接続を閉じると `read-line` は `nil` を返します。

以下の例は自己完結しています:
[`rontolisp:tcp-listen`](rontolisp-tcp-listen.md) でエフェメラルポートを
listen し、ループバック経由で自分自身へ接続して、
[`rontolisp:tcp-accept`](rontolisp-tcp-accept.md) を通して 1 行をエコーします:

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

典型的なクライアントは固定のホストとポートへ接続し、サーバーが閉じるまで行を
やり取りします:

```console
(let ((sock (rontolisp:tcp-connect "127.0.0.1" 7777)))
  (write-line "hello" sock)
  (print (read-line sock))   ; the server's reply
  (close sock))
```

## バックエンドごとの対応

- **インタープリタ**と **JVM**: JDK の `java.net.Socket` を使用し、`host` には
  ホスト名と IP リテラルのどちらも指定できます。接続失敗(接続拒否など)は
  エラーを通知します。文字列でない `host` や `(INTEGER 0 65535)` の範囲外の `port` は、接続の前に
  その演算子の `type-error` を通知します。
- **WASM**: コンポーネント専用で、`wasi:sockets@0.3.0` 上で動作します
  (WASI 0.3 ネイティブで、0.2 ハイブリッドは不要)。
  `host` にはホスト名と IPv4 リテラルを指定できます。ホスト名は
  `wasi:sockets/ip-name-lookup@0.3.0` で解決し、その答えのうち最初の IPv4
  アドレスに接続します(接続は IPv4 のみ)。`--component`
  でコンパイルし、`wasmtime run -S tcp=y
  -S inherit-network=y` で実行します(wasmtime 46+)。ホスト名には
  `-S allow-ip-name-lookup=y` も付けます(IPv4 リテラルと `localhost` は解決を
  要しません)。接続失敗や IPv4 アドレスに解決できないホスト名は、ハンドルの代わりに `nil` を返します。`-S` フラグなしでも
  コンポーネントは起動しますが、すべてのソケット操作が失敗して `nil` になります。
  tcp 系関数は Preview 1(コアモジュール)モードでもコンパイルは通りますが、
  呼び出すたびに動作するバックエンドを名指しする呼び出し時エラーになります。
- **ネイティブ実行ファイル**(`--native`): インタープリタ/JVM と同じです --
  ランナーがソケットを開き、`host` にはホスト名も IP リテラルも指定でき、
  接続失敗は同じエラーを通知します
  ([ネイティブ実行ファイルの TCP ソケット](../../compiling/native.md#tcp-sockets))。
- **ブラウザプレイグラウンド**: 非対応 — ブラウザのサンドボックスには生の TCP
  ソケットが存在しないため、すべての tcp 関数はエラーを通知します。

## 制限事項

- TCP のみ(UDP は未対応): 通信は平文です。暗号化されたクライアント接続には
  [`rontolisp:tls-connect`](rontolisp-tls-connect.md) を使ってください
  (インタープリタ/JVM のみ)。
- WASM コンポーネントバックエンドの接続は IPv4 のみです(IPv6 リテラルは
  使えず、IPv6 アドレスしか持たないホスト名は失敗します)。
- `read`(S 式リーダー)はソケットハンドルでは動作しません。行またはバイトを
  読み込み、明示的にパースしてください(例:
  [`read-from-string`](read-from-string.md))。
