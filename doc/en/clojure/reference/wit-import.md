# rontolisp.wit/import

`(import "path.wit" options)` with `[rontolisp.wit :as wit]`

Reads the WIT file -- relative to the file naming it -- and binds the functions of one of its
interfaces as the vars of a namespace of their own ([WIT contracts](wit.md)). The options map:

- `:interface` -- the interface: its full id (`"wasi:keyvalue/store@0.2.0"`), the id without
  its version, or its bare name when the file defines it once. Required.
- `:as` -- an alias for the interface's namespace in the current one.
- `:refer` -- a vector of member names to refer, or `:all`. One of `:as` and `:refer` is
  required: nothing else reaches the vars.
- `:from` -- the import module of a WASM core module; the interface's bare name by default.
- `:field-style` -- `:camel` (the default: `create-shader` imports `createShader`) or
  `:kebab` (the label as written), for a WASM core module's import fields.

A function is the var of its WIT name; a resource's method takes the handle first and is
prefixed with the resource (`bucket.get` is `kv/bucket-get`), its constructor is
`<resource>-new`, and its release `<resource>-drop`. The vars are functions like a `defn`'s:
`#'kv/open` prints `#'wasi:keyvalue.store@0.2.0/open` (the namespace is the interface's id),
and `(map kv/bucket-get ...)` takes one. The import binds the interface once per program; a
second one only wires its alias and refers.

```console
$ cat math.wit
package example:host@0.1.0;

interface math {
  add-ints: func(a: s32, b: s32) -> s32;
  is-even: func(n: s32) -> bool;
}
$ cat app.clj
(ns app (:require [rontolisp.wit :as wit]))

(wit/import "math.wit" {:interface "example:host/math" :as math :refer [is-even]})

(defn report {:wasm/export {:params [:int] :returns :string}} [n]
  (str (math/add-ints n 1) " " (is-even n)))
$ rontolisp app.clj -o app.wasm --no-wasi --emit-js-glue
```

The calls lower to the WIT's own bindings on each target: the provider on the interpreter
and the JVM ([provide](wit-provide.md)), one `math.addInts` / `math.isEven` host import of a
WASM core module, a canonical-ABI import under `--component`; a core module imports the
members the program calls. Each value crosses in its Clojure spelling -- a record as a map, a
variant's case as a keyword or `[:case payload]` -- and a `result`'s error arm throws an
`ExceptionInfo` holding the error value ([What crosses](wit.md#what-crosses)). A member
whose types reach a stream or a future, or an `async func`, is not bound, and a reference to
it is refused naming its WIT line.
