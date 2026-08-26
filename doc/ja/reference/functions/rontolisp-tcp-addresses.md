# rontolisp:tcp-local-address rontolisp:tcp-peer-address rontolisp:tcp-peer-port

`(rontolisp:tcp-local-address handle)` -- `(rontolisp:tcp-peer-address handle)` -- `(rontolisp:tcp-peer-port handle)`

TCP ハンドルのアドレス取得関数です。`tcp-local-address` はリスナーまたは
ソケットハンドルのローカル(バインド先)IP アドレスを文字列で返します。
`tcp-peer-address` と `tcp-peer-port` は接続済みソケットハンドルのリモート
IP アドレス(文字列)とリモートポート(整数)を返します。
[`rontolisp:tcp-local-port`](rontolisp-tcp-local-port.md) と合わせて、
[usocket シム](usocket-accessors.md)の `usocket:get-local-*` /
`usocket:get-peer-*` アクセサを支えています。

```lisp
(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
       (port (rontolisp:tcp-local-port listener))
       (client (rontolisp:tcp-connect "127.0.0.1" port))
       (server (rontolisp:tcp-accept listener))
       (peer (rontolisp:tcp-peer-address client)))
  (close server)
  (close client)
  (close listener)
  peer) ; => "127.0.0.1"
```

peer 系アクセサはリスナーハンドルを拒否します(リスナーに peer は
ありません):

```console
$ rontolisp
CL-USER> (setq l (rontolisp:tcp-listen 0 "127.0.0.1"))
CL-USER> (rontolisp:tcp-peer-address l)
Error: tcp-peer-address expects a connected socket handle
```

## バックエンドごとの対応

- **インタープリタ**と **JVM**: 対応する `java.net.Socket` / `ServerSocket` の
  `getLocalAddress()` / `getInetAddress()` / `getPort()` を使用します。種類の
  合わないハンドルはエラーを通知します(インタープリタ)/キャストエラーで
  失敗します(JVM)。
- **WASM**: コンポーネントモードのみ -- 3 つともインタープリタ/JVM と
  まったく同じように実際のアドレスとポートを返します。失敗時や種類の
  合わないハンドルではエラーを通知せず `nil` を返します(splice された
  usocket プログラムがそのまま動くようにするためです)。Preview 1
  (コアモジュール)モードでは他の tcp 組み込みと同様、呼び出し時エラーです。
- **ブラウザプレイグラウンド**: 非対応。
