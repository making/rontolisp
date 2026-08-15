# uiop:getenv

`(uiop:getenv name)` / `(setf (uiop:getenv name) value)`

指定した名前の環境変数の値を文字列として返します。変数が設定されていない場合は `nil` を返します。Common Lisp に `getenv` はないため、この関数は `uiop` パッケージに置かれています -- 処理系非依存のライブラリがすでに使っている可搬な綴りであり、修飾なしの `getenv` は存在しません。4 つすべてのバックエンドで動作します。WASM バックエンドは Preview 1 では実際のホスト環境を、`--component` モードでは `wasi:cli/environment@0.3.0` を読みます（`wasmtime serve` 上の `rontolisp:http-handler` コンポーネントも同じで、そのためにこのインタフェースをインポートします）。変数を見えるようにするには wasmtime に `--env`/`-S inherit-env` を渡してください。

`(setf (uiop:getenv name) value)` は、以降の読み取りがホストより先に参照する**オーバーライド**を記録します。値が `nil` の場合はその変数を未設定として読ませます。プロセス環境そのものは変更しません。どのバックエンドでも変更できない（JVM は原理的に不可、WASI は読み取り専用）ため、オーバーライドはこのプログラムの実行中だけ有効です -- [uiop/os](../uiop/os.md#environment-variables) を参照してください。

```lisp
(uiop:getenv "PATH")
```

結果はホストが変数に割り当てた値そのものなので非決定的です。`(uiop:getenv "DEFINITELY_UNSET")` は `nil` を返します。

```lisp
(setf (uiop:getenv "RONTOLISP_EXAMPLE_VAR") "set-here")
(uiop:getenv "RONTOLISP_EXAMPLE_VAR")   ; => "set-here"
```
