# rontolisp.wit/export

`(export "path.wit")` or `(export "path.wit" {:world "name"})` with `[rontolisp.wit :as wit]`

Declares that the program implements a WIT world: each export of the world is the var its
label names in the current namespace -- a `defn` defined anywhere in the file, a referred
var -- and the module exports it with the world's types ([WIT contracts](wit.md)). `:world`
names the world when the file declares several. The WIT file is relative to the file naming
it, like `load`.

```console
$ cat wit/greeter.wit
package example:greeter@0.1.0;

world greeter {
  export greet: func(name: string) -> string;
  export is-short: func(name: string) -> bool;
}
$ cat greeter.clj
(ns greeter (:require [rontolisp.wit :as wit]))

(wit/export "wit/greeter.wit")

(defn greet [name] (str "Hello, " name "!"))
(defn is-short [name] (< (count name) 5))
$ rontolisp greeter.clj -o greeter.wasm --component --emit-wit
$ wasmtime run --invoke 'is-short("Grace")' greeter.wasm
false
```

The world is the program's whole export list: a [rontolisp.wasm/export](wasm-export.md)
beside it is refused. The world is checked when the program compiles, on every target -- an
export naming no var, a `defn` with no arity taking its parameters, a type the export
boundary does not carry, an `async func` -- each refused naming the WIT line. A `bool`
crosses as `true` or `false`, and an export that is no single-arity `defn` taking exactly its
parameters goes through a function of that arity calling the var, as for
[rontolisp.wasm/export](wasm-export.md). `--emit-wit` writes the world back, parameter names
included.

In a REPL, a world is checked against what is defined so far: define its functions first.
