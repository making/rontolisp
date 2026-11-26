# usocket パッケージの関数

`usocket` パッケージは、[usocket](https://github.com/usocket/usocket) API を
`rontolisp:tcp-*` 組み込みの上で再現する互換シムです。Postmodern の
cl-postgres ソケット層のような既存の Common Lisp ネットワークコードが、
より少ない変更で動きます。**Common Lisp の一部ではありません**。シンボルは
`usocket:` 修飾子付きで参照します。このシムではソケットはストリームハンドル
そのものなので、`socket-stream` は恒等関数で、標準のストリーム関数が
ソケットにそのまま使えます。パッケージは最初の使用時にロードされ、組み込み
ASDF システム `"usocket"` でもあります(`asdf:load-system`、`ql:quickload`、
`:depends-on ("usocket")` をダウンロードなしで充足)。対応は TCP のみ --
UDP(`socket-send` / `socket-receive`)、`wait-for-input`、`socket-server`、
コンディション階層(`handler-case` での `usocket:socket-error`)は
非対応です。変数 `usocket:*wildcard-host*`(`"0.0.0.0"`)と
`usocket:*auto-port*`(`0`)が提供されます。全体像と制限の一覧は
[TCPソケットガイド](../../guides/tcp-sockets.md#the-usocket-compatible-shim)を参照して
ください。

| 関数 | 例 | 結果 |
|----------|---------|--------|
| `usocket:socket-connect` | `(usocket:socket-connect "localhost" 5432 :element-type '(unsigned-byte 8))` | ブロッキングTCP接続を開く。`:protocol :datagram` はエラー、他のオプションは受理して無視 |
| `usocket:socket-listen` | `(usocket:socket-listen usocket:*wildcard-host* usocket:*auto-port*)` | リスニングTCPソケットをバインド(usocket流にホストが先) |
| `usocket:socket-accept` | `(usocket:socket-accept listener)` | クライアント接続を待つ(ブロッキング) |
| `usocket:socket-stream` | `(read-line (usocket:socket-stream sock))` | ソケットのストリーム(このシムでは恒等関数) |
| `usocket:socket-close` | `(usocket:socket-close sock)` | ソケットまたはリスナーを閉じる |
| `usocket:get-local-port` | `(usocket:get-local-port listener)` | ローカルにバインドされたポート(エフェメラルポートの読み戻し) |
| `usocket:get-local-address` | `(usocket:get-local-address listener)` | ローカルにバインドされたIPアドレス(文字列) |
| `usocket:get-peer-address` | `(usocket:get-peer-address sock)` | 接続済みソケットのリモートIPアドレス |
| `usocket:get-peer-port` | `(usocket:get-peer-port sock)` | 接続済みソケットのリモートポート |
| `usocket:get-local-name` | `(usocket:get-local-name sock)` | ローカルのアドレスとポートを `(values address port)` で返す |
| `usocket:get-peer-name` | `(usocket:get-peer-name sock)` | リモートのアドレスとポートを `(values address port)` で返す |
| `usocket:host-to-hostname` | `(usocket:host-to-hostname #(192 168 0 1))` | ホスト指定子 (文字列・4 要素ベクタ・ホストバイトオーダ整数・`nil`) をホスト名／ドット区切り文字列として返す |
| `usocket:get-host-by-name` | `(usocket:get-host-by-name "example.com")` | ライト版: 名前解決せず `host-to-hostname` で描画して返す — 名前解決のプリミティブがどのバックエンドにもなく、そのアドレスが届くソケット呼び出しが実際の解決を行うため |

`with-*` 便利マクロ(`usocket:with-client-socket` / `with-connected-socket` /
`with-server-socket` / `with-socket-listener`)は
[マクロページ](../macros.md)に一覧があり、
[リファレンスページ](../macros/usocket-with-macros.md)で説明しています。
インタープリタと JVM ではあらゆる脱出時にソケットを閉じます
([`unwind-protect`](../special-forms/unwind-protect.md) に展開されます)。
WASM コンポーネントバックエンドでは正常終了時のみ閉じます。

