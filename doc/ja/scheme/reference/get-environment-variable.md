# get-environment-variable

`(get-environment-variable name)`

環境変数 `name` の値を文字列で返します。未設定なら `#f` です。空文字列を設定した変数は `""` を返します。インタープリタで実行するプログラムは `rontolisp` プロセスの環境を読み、コンパイルしたプログラムはホストの環境を読みます。WebAssembly プログラムに渡るのはホストが渡したものだけです（`wasmtime run --env NAME=value`）。`--no-wasi` のモジュールには環境変数がありません。

```console
$ cat env.scm
(import (scheme base) (scheme write) (scheme process-context))
(write (get-environment-variable "GREETING"))
$ GREETING="hello world" rontolisp env.scm
"hello world"
$ rontolisp env.scm -o env.wasm && wasmtime run --env GREETING=hi env.wasm
"hi"
```

```scheme
(write (get-environment-variable "RONTOLISP_UNSET"))
```

```
#f
```
