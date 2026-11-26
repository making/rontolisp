# asdf:operate

`(asdf:operate operation system &rest keys)`

本物の ASDF の入口と同じく、システムに operation を perform します。`asdf:load-op` と
`asdf:load-source-op` では [`asdf:load-system`](asdf-load-system.md) と同じです（キーは渡され、
`load-system` と同じく無視されます）。`asdf:test-op` では
[`asdf:test-system`](asdf-test-system.md)、`asdf:build-op` では `asdf:make` と同じで、システムの
`:build-operation` を perform します。`asdf:program-op`(または deploy の `deploy-op` のようなサブクラス)
では、システムをロードしてその実行ファイルをビルドします
([ASDF システムから実行ファイルをビルドする](../../compiling/asdf-programs.md))。それ以外の
operation はエラーを通知します。ここではシステムをソースからロードするので、`compile-op` だけを単独で
行っても生成するものがなく、バンドルも作らないからです。[`asdf:oos`](asdf-oos.md) は旧名です。
`operation` はクラス名か operation のインスタンス
（[`asdf:perform`](asdf-perform.md) を参照）、`system` は名前の指定子かシステムの
メタオブジェクトです。operation のインスタンスを返します。

```console
(asdf:operate 'asdf:load-op :my-lib)   ; same as (asdf:load-system :my-lib)
(asdf:operate 'asdf:test-op "my-app")  ; same as (asdf:test-system "my-app")
```

## バックエンドサポート

4 つすべてのバックエンドで動作します。コンパイルパスでは、クォートした `load-op` とリテラルの名前の
トップレベル呼び出しはリテラルの `asdf:load-system` と同じくシステムをスプライスし、`test-op` では
リテラルの `asdf:test-system` と同じです。それ以外の場所の `load-op` は、プログラムが既にスプライスした
システムには答えを返し、それ以外のシステムでは実行時の `load-system` と同じくエラーを通知します。
実行ファイルをビルドできるのは、ビルドの走るインタプリタだけです: コンパイル済みのプログラムでは
`program-op` は `rontolisp build` を名指しするエラーを通知します。
