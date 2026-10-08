# rontolisp.wasm/export

`(export var options)` with `[rontolisp.wasm :as wasm]`, or `{:wasm/export options}` in a
`defn`'s attr map (or its name's metadata)

Exports the function `var` names, so the host can call it ([WASM host functions](wasm.md)).
The options map:

- `:as` -- the name the host calls it by; the var's name by default.
- `:params` -- a vector of type keywords, one per argument; none by default.
- `:returns` -- the result's type keyword; `:void` by default (the result is dropped).

The var resolves where the declaration stands, after the whole file has lowered: it may be
defined below, and a redefined `defn` exports its newest definition. A single-arity `defn`
taking exactly the declared parameters is exported as itself, the way a hand-written
`rontolisp:wasm-export` names a `defun`. Anything else is exported through a function of the
declared arity that calls the var: a `defn` with several arities or a rest parameter, a
`def`'d function, a multimethod. A `defn` with no arity taking that many arguments is refused,
naming the export. The metadata stays on the var as written.

```console
$ cat calc.clj
(ns calc (:require [rontolisp.wasm :as wasm]))

(defn positive? {:wasm/export {:as "is-positive" :params [:s32] :returns :bool}} [n]
  (> n 0))

(defn total ([a] a) ([a b] (+ a b)))
(wasm/export total {:params [:s32 :s32] :returns :s32})
$ rontolisp calc.clj -o calc.wasm --component
$ wasmtime run --invoke 'is-positive(-3)' calc.wasm
false
$ wasmtime run --invoke 'total(2, 40)' calc.wasm
42
```

A program implementing a WIT world declares its exports with
[rontolisp.wit/export](wit-export.md) instead; the two are not combined. On the interpreter
and the JVM nothing is exported and the function stays an ordinary one.
