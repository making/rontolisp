# usocket:socket-listen

`(usocket:socket-listen host port &key reuse-address backlog element-type)`

`host` と `port` にリスニング TCP ソケットをバインドし、リスナーを返します --
[`rontolisp:tcp-listen`](rontolisp-tcp-listen.md) の usocket 互換ラッパーです
(引数順が逆で、usocket はホストを先に渡します)。`host` が
`usocket:*wildcard-host*`(`"0.0.0.0"`)または `nil` なら全インタフェースで
listen します。`port` が `usocket:*auto-port*`(`0`)なら空いている
エフェメラルポートを選び、`usocket:get-local-port` で読み戻せます。
キーワード引数は互換性のために受理して無視します(backlog はランタイムの
デフォルト)。

```lisp
(let* ((listener (usocket:socket-listen usocket:*wildcard-host* usocket:*auto-port*))
       (port (usocket:get-local-port listener)))
  (usocket:socket-close listener)
  (> port 0)) ; => t
```

接続の受け付けは [`usocket:socket-accept`](usocket-socket-accept.md) で
行います。

## バックエンドごとの対応

- **インタープリタ**と **JVM**: フル対応。
- **WASM**: コンポーネントモードのみ。バインド失敗は `nil` を返します。
  Preview 1 はコンパイルエラーです。
- **ブラウザプレイグラウンド**: 非対応。
