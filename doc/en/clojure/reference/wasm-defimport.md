# rontolisp.wasm/defimport

`(defimport name options)` with `[rontolisp.wasm :as wasm]`

Defines the var `name` in the current namespace as a function the host provides: compiled to
a WASM core module, a call to it calls the host's import ([WASM host functions](wasm.md)). The
options map:

- `:from` -- the import module, `"env"` by default (a JavaScript host's import-object key,
  wasmtime's `--preload` name).
- `:as` -- the field inside the module; the var's name as written by default.
- `:params` -- a vector of type keywords, one per argument; none by default.
- `:returns` -- the result's type keyword; `:void` (nil) by default.

It is a definition like `defn`: a call above it in the file reaches it, a REPL echoes the var,
and `#'name` and `(map name ...)` take the function. It lowers to one
`rontolisp:wasm-import` -- with a wrapper function around it when a `:bool` or `:s-expr` value
crosses ([Types](wasm.md#types)).

```console
$ cat clock.clj
(ns clock (:require [rontolisp.wasm :as wasm]))

(wasm/defimport now-ms {:from "env" :as "nowMs" :returns :float})
(wasm/defimport log-line {:from "env" :as "log" :params [:string]})

(defn tick {:wasm/export {:returns :float}} []
  (log-line (str "tick at " (now-ms)))
  (now-ms))
$ rontolisp clock.clj -o clock.wasm --no-wasi --emit-js-glue
```

On the interpreter and the JVM there is no host: the function takes the declared arity and
throws `UnsupportedOperationException` when called. Under `--component` the directive is
refused; a component calls a WIT interface instead ([rontolisp.wit/import](wit-import.md)).
