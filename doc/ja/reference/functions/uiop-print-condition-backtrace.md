# uiop/image:print-condition-backtrace

`(uiop/image:print-condition-backtrace condition &key stream count)`

`condition` のレポートを `stream`（デフォルトは `*error-output*`）へ出力します。
**ライト版**: どのバックエンドも Lisp レベルのコールスタックを持たないため、出力
すべきバックトレースが存在せず、レポートはコンディション自体だけになります。`count`
は受け取って無視します。本物の UIOP も、バックトレース API のない処理系では同じ形に
フォールバックします。

```lisp
(handler-case (error "boom")
  (error (c) (uiop/image:print-condition-backtrace c :stream *standard-output*)))
```

```
boom
```

この名前は上流と同じく `uiop/image` パッケージに属し、`uiop` パッケージが再エクスポート
しています -- `uiop:print-condition-backtrace` は同じ関数を指します。これを必要とするのは
`lack-middleware-backtrace` です。

## バックエンドサポート

4 つすべてのバックエンドで動作します: rontolisp 自身で書かれたプレリュード定義であり、
使用時にプログラムへ組み込まれてコンパイルされます。
