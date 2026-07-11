# usocket:get-local-port usocket:get-local-address usocket:get-local-name usocket:get-peer-address usocket:get-peer-port usocket:get-peer-name

`(usocket:get-local-port socket)` -- `(usocket:get-local-address socket)` -- `(usocket:get-local-name socket)` -- `(usocket:get-peer-address socket)` -- `(usocket:get-peer-port socket)` -- `(usocket:get-peer-name socket)`

usocket のアドレスアクセサです。
[`rontolisp:tcp-local-port`](rontolisp-tcp-local-port.md) /
[`tcp-local-address` / `tcp-peer-address` / `tcp-peer-port`](rontolisp-tcp-addresses.md)
組み込みの上に載っています。`get-local-*` アクセサはリスナーと接続済み
ソケットの両方で動き(主な用途は `usocket:*auto-port*` で listen した後の
エフェメラルポートの読み戻し)、`get-peer-*` アクセサは接続済みソケット
専用です。

```lisp
(let* ((listener (usocket:socket-listen "127.0.0.1" usocket:*auto-port*))
       (port (usocket:get-local-port listener)))
  (usocket:socket-close listener)
  (> port 0)) ; => t
```

`get-local-name` / `get-peer-name` は `(values address port)` を返します:
`multiple-value-bind` は両方を受け取り、通常の単一値コンテキストでは
アドレスを受け取ります。

```lisp
(let ((listener (usocket:socket-listen "127.0.0.1" usocket:*auto-port*)))
  (multiple-value-bind (address port) (usocket:get-local-name listener)
    (usocket:socket-close listener)
    (list address (> port 0)))) ; => ("127.0.0.1" t)
```

## バックエンドごとの対応

- **インタープリタ**と **JVM**: フル対応。
- **WASM**: コンポーネントモードのみ。`get-local-port` は動きますが、
  アドレス系・peer 系アクセサは `nil` を返します(ソケットアダプタには
  接続されていません)。Preview 1 はコンパイルエラーです。
- **ブラウザプレイグラウンド**: 非対応。
