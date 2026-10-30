# asdf:operate

`(asdf:operate operation system &rest keys)`

本物の ASDF の入口と同じく、システムに operation を perform します。`asdf:load-op`
では [`asdf:load-system`](asdf-load-system.md) と同じです（キーは渡され、
`load-system` と同じく無視されます）。`asdf:test-op` では
[`asdf:test-system`](asdf-test-system.md) と同じです。それ以外の operation はエラーを
通知します。ここではシステムをソースからロードするので、`compile-op` だけを単独で
行っても生成するものがないからです。`operation` はクラス名か operation のインスタンス
（[`asdf:perform`](asdf-perform.md) を参照）、`system` は名前の指定子かシステムの
メタオブジェクトです。operation のインスタンスを返します。

```console
(asdf:operate 'asdf:load-op :my-lib)   ; same as (asdf:load-system :my-lib)
(asdf:operate 'asdf:test-op "my-app")  ; same as (asdf:test-system "my-app")
```

## バックエンドサポート

4 つすべてのバックエンドで動作します。コンパイルパスでは、`load-op` はプログラムが
既にスプライスしたシステムについては答えを返し、それ以外のシステムでは実行時の
`load-system` と同じくエラーを通知します。
