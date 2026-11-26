# get-environment-variable

`(get-environment-variable name)`

The value of the environment variable `name` as a string, or `#f` when it is unset. A variable set to the empty string answers `""`. An interpreted program reads the environment of the `rontolisp` process; a compiled one reads its host's, which hands a WebAssembly program only what it passes in (`wasmtime run --env NAME=value`). A `--no-wasi` module has none.

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
