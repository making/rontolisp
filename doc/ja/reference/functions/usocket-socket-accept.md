# usocket:socket-accept

`(usocket:socket-accept socket &key element-type)`

クライアントが接続してくるまでブロックし、受け付けた接続ソケットを返します
-- [`rontolisp:tcp-accept`](rontolisp-tcp-accept.md) の usocket 互換
ラッパーです。`:element-type` は互換性のために受理して無視します
(rontolisp のソケットハンドルは常に双方向です)。

```lisp
(let* ((listener (usocket:socket-listen "127.0.0.1" usocket:*auto-port*))
       (port (usocket:get-local-port listener))
       (client (usocket:socket-connect "127.0.0.1" port))
       (server (usocket:socket-accept listener))
       (peer (usocket:get-peer-address server)))
  (usocket:socket-close server)
  (usocket:socket-close client)
  (usocket:socket-close listener)
  peer) ; => "127.0.0.1"
```

## バックエンドごとの対応

- **インタープリタ**と **JVM**: フル対応。
- **WASM**: コンポーネントモードのみ。Preview 1 はコンパイルエラーです。
- **ブラウザプレイグラウンド**: 非対応。
