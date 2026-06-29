# getenv

`(getenv name)`

指定した名前の環境変数の値を文字列として返します。変数が設定されていない場合は `nil` を返します。3 つすべてのバックエンドで動作します。WASM バックエンドは Preview 1 では実際のホスト環境を、`--component` モードでは `wasi:cli/environment@0.3.0` を読むため、変数を見えるようにするには wasmtime に `--env`/`-S inherit-env` を渡してください。

```lisp
(getenv "PATH")
```

結果はホストが変数に割り当てた値そのものなので非決定的です。`(getenv "DEFINITELY_UNSET")` は `nil` を返します。
