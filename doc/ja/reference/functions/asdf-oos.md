# asdf:oos

`(asdf:oos operation system &rest keys)`

[`asdf:operate`](asdf-operate.md) の旧名で、引数も戻り値も同じです。
`(asdf:oos 'asdf:load-op :my-lib)` はシステムをロードします。ロードの別の書き方として
`(asdf:load-systems :a :b)`、`(asdf:load-systems* '(:a :b))`、
`(asdf:require-system :a)` があります。`(asdf:make :a)` はシステムの `:build-operation`
を実行し、既定ではロードです。それ以外の build operation は拒否します。
`asdf:compile-system` も同様です。ここではイメージのダンプも fasl の書き出しも
行わないからです。

```console
(asdf:oos 'asdf:load-op :my-lib)   ; same as (asdf:load-system :my-lib)
(asdf:oos 'asdf:test-op "my-app")  ; same as (asdf:test-system "my-app")
```

## バックエンドサポート

4 つすべてのバックエンドで動作します。コンパイルパスでは、名前がリテラルのトップレベル呼び出しは
リテラルの `asdf:load-system` と同じくシステムをスプライスします。それ以外の場所の呼び出しは、
プログラムが既にスプライスしたシステムには答えを返し、それ以外のシステムではエラーを通知します。
