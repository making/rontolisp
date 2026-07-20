# rontolisp:tcp-local-port

`(rontolisp:tcp-local-port handle)`

リスナーまたはソケットハンドルにバインドされたローカル TCP ポートを整数で
返します。主な用途はポート `0`(OS が選ぶエフェメラルポート)で listen した後に
実際のポートを取得することです。テストや自己完結の例がポートをハードコード
せずに済みます:

```lisp
(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
       (port (rontolisp:tcp-local-port listener)))
  (close listener)
  (> port 0))   ; => T
```

接続済みのソケットハンドルにも使え、その場合は接続のローカル(クライアント側)
ポートを返します:

```console
(let ((sock (rontolisp:tcp-connect "127.0.0.1" 7777)))
  (print (rontolisp:tcp-local-port sock))   ; the ephemeral client port
  (close sock))
```

## バックエンドごとの対応

- **インタープリタ**と **JVM**: 対応する `java.net.ServerSocket` / `Socket` の
  `getLocalPort()` を使用します。ソケットでもリスナーでもないハンドルは
  エラーを通知します(インタープリタ)/キャストエラーで失敗します(JVM)。
- **WASM**: コンポーネント専用で、`wasi:sockets` の `get-local-address` を
  使用します。ソケットでもリスナーでもないハンドルには `nil` を返します。
  Preview 1(コアモジュール)モードではコンパイルエラーです。
- **ブラウザプレイグラウンド**: 非対応。
